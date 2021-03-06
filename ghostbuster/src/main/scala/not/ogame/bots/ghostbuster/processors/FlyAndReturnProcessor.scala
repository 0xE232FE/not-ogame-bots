package not.ogame.bots.ghostbuster.processors

import java.time.ZonedDateTime

import cats.implicits._
import io.chrisdavenport.log4cats.Logger
import monix.eval.Task
import not.ogame.bots.FleetMissionType.Deployment
import not.ogame.bots._
import not.ogame.bots.ghostbuster.executor._
import not.ogame.bots.ghostbuster.ogame.OgameAction
import not.ogame.bots.ghostbuster.FLogger

import scala.concurrent.duration._
import scala.util.Random

class FlyAndReturnProcessor(config: FlyAndReturnConfig, ogameDriver: OgameDriver[OgameAction])(
    implicit executor: OgameActionExecutor[Task],
    clock: LocalClock
) extends FLogger {
  def run(): Task[Unit] = {
    if (config.isOn) {
      for {
        planets <- ogameDriver.readPlanets().execute()
        from = planets.find(p => p.id == config.from).get
        to = planets.find(p => p.id == config.to).get
        _ <- loop(from, to)
      } yield ()
    } else {
      Task.never
    }
  }

  private def loop(from: PlayerPlanet, to: PlayerPlanet): Task[Unit] = {
    (for {
      allMyFleets <- ogameDriver.readMyFleets()
      thisMyFleet = allMyFleets.fleets.find(isThisMyFleet(_, from, to))
      nextStepTime <- processMyFleet(thisMyFleet, from, to)
    } yield nextStepTime).execute().flatMap { nextStepTime =>
      Logger[Task].info(s"Waiting to next step til $nextStepTime") >>
        executor.waitTo(nextStepTime) >>
        withRetry(loop(from, to))("flyAndReturn")
    }
  }

  private def processMyFleet(
      thisMyFleet: Option[MyFleet],
      from: PlayerPlanet,
      to: PlayerPlanet
  ): OgameAction[ZonedDateTime] = {
    thisMyFleet match {
      case Some(fleet) if !fleet.isReturning =>
        Logger[OgameAction].info(s"Found non-returning fleet: ${pprint.apply(fleet)}") >> returnOrWait(fleet)
      case Some(fleet) if fleet.isReturning =>
        Logger[OgameAction].info(s"Found returning fleet ${pprint.apply(fleet)}").as(fleet.arrivalTime.plus(3 seconds))
      case None =>
        sendFleetOr(from, to) {
          Logger[OgameAction].warn(s"There was no fleet on $from planet. Sleeping 15 minutes...").as(clock.now().plus(15 minutes))
        }
    }
  }

  private def sendFleetOr(from: PlayerPlanet, to: PlayerPlanet)(fallback: OgameAction[ZonedDateTime]) = {
    for {
      resources <- new ResourceSelector[OgameAction](deuteriumSelector = Selector.decreaseBy(config.remainDeuterAmount))
        .selectResources(ogameDriver, from)
      fleetPage <- ogameDriver.readFleetPage(from.id)
      shipsToSend = new FleetSelector(Map(ShipType.Explorer -> Selector.decreaseBy(config.explorersToLeft)))(fleetPage)
      nextTime <- if (shipsToSend.values.sum > 0) {
        Logger[OgameAction].info(s"Fleet is on planet $from. Sending fleet to $to") >>
          send(from, to, resources, shipsToSend).as(clock.now())
      } else {
        fallback
      }
    } yield nextTime
  }

  private def returnOrWait(fleet: MyFleet): OgameAction[ZonedDateTime] = {
    if (isCloseToArrival(fleet)) {
      Logger[OgameAction].info("Returning fleet!") >>
        ogameDriver.returnFleet(fleet.fleetId).as(clock.now())
    } else {
      chooseTimeWhenClickReturn(fleet).pure[OgameAction]
    }
  }

  private def send(from: PlayerPlanet, to: PlayerPlanet, resources: Resources, ships: Map[ShipType, Int]): OgameAction[Unit] = {
    val fleetSpeed = Random.shuffle(config.speeds).head
    ogameDriver.sendFleet(
      SendFleetRequest(
        from = from,
        ships = SendFleetRequestShips.Ships(ships),
        targetCoordinates = to.coordinates,
        fleetMissionType = Deployment,
        resources = FleetResources.Given(resources),
        speed = fleetSpeed
      )
    )
  }

  private def isCloseToArrival(fleet: MyFleet) = {
    fleet.arrivalTime.minus(config.safeBuffer).minus(config.randomUpperLimit).isBefore(clock.now())
  }

  private def chooseTimeWhenClickReturn(fleet: MyFleet): ZonedDateTime = {
    fleet.arrivalTime.minus(config.safeBuffer).minusSeconds(Random.nextLong(config.randomUpperLimit.toSeconds))
  }

  private def isThisMyFleet(fleet: MyFleet, from: PlayerPlanet, to: PlayerPlanet): Boolean = {
    fleet.from == from.coordinates && fleet.to == to.coordinates && fleet.fleetMissionType == FleetMissionType.Deployment
  }
}

case class FlyAndReturnConfig(
    from: PlanetId,
    to: PlanetId,
    isOn: Boolean,
    safeBuffer: FiniteDuration,
    randomUpperLimit: FiniteDuration,
    remainDeuterAmount: Int,
    speeds: List[FleetSpeed],
    explorersToLeft: Int
)
