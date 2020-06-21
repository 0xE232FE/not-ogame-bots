package not.ogame.bots.ghostbuster.processors

import java.time.ZonedDateTime

import io.chrisdavenport.log4cats.Logger
import monix.eval.Task
import not.ogame.bots.FleetMissionType.Deployment
import not.ogame.bots._
import not.ogame.bots.ghostbuster.executor._
import not.ogame.bots.ghostbuster.{FLogger, FlyAndReturnConfig}

import scala.util.Random
import scala.concurrent.duration._
import cats.implicits._
import not.ogame.bots.ghostbuster.ogame.OgameAction

class FlyAndReturnProcessor(config: FlyAndReturnConfig, ogameDriver: OgameDriver[OgameAction])(
    implicit executor: OgameActionExecutor[Task],
    clock: LocalClock
) extends FLogger {
  def run(): Task[Unit] = { //TODO add support for other way
    if (config.isOn) {
      (for {
        planets <- ogameDriver.readPlanets().execute()
        from = planets.find(p => p.id == config.from).get
        to = planets.find(p => p.id == config.to).get
        _ <- loop(from, to)
      } yield ())
        .onError(e => Logger[Task].error(e)(s"restarting flyAndReturn processor ${e.getMessage}"))
        .onErrorRestartIf(_ => true)
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
        loop(from, to)
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
        Logger[OgameAction].info(s"Found returning fleet ${pprint.apply(fleet)}") >> fleet.arrivalTime.plus(3 seconds).pure[OgameAction]
      case None =>
        Logger[OgameAction].info(s"Fleet is on planet $from. Sending fleet to $to")
        new ResourceSelector[OgameAction](deuteriumSelector = Selector.decreaseBy(config.remainDeuterAmount))
          .selectResources(ogameDriver, from)
          .flatMap { resources =>
            send(from, to, resources).as(clock.now())
          }
    }
  }

  private def returnOrWait(fleet: MyFleet): OgameAction[ZonedDateTime] = {
    if (isCloseToArrival(fleet)) {
      Logger[OgameAction].info("Returning fleet!") >>
        ogameDriver.returnFleet(fleet.fleetId).as(clock.now())
    } else {
      chooseTimeWhenClickReturn(fleet).pure[OgameAction]
    }
  }

  private def send(from: PlayerPlanet, to: PlayerPlanet, resources: Resources): OgameAction[Unit] = {
    val fleetSpeed = Random.shuffle(List(FleetSpeed.Percent10, FleetSpeed.Percent20)).head
    ogameDriver
      .sendFleet(
        SendFleetRequest(
          from = from,
          ships = SendFleetRequestShips.AllShips,
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
