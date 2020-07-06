package not.ogame.bots.ghostbuster.processors

import cats.implicits._
import io.chrisdavenport.log4cats.Logger
import monix.eval.Task
import not.ogame.bots._
import not.ogame.bots.ghostbuster.FLogger
import not.ogame.bots.ghostbuster.executor.{OgameActionExecutor, _}
import not.ogame.bots.ghostbuster.ogame.{OgameAction, OgameActionDriver}

import scala.concurrent.duration.FiniteDuration

class SendShipsProcessor(config: SendShipConfig, driver: OgameActionDriver)(implicit executor: OgameActionExecutor[Task]) extends FLogger {
  def run(): Task[Unit] = {
    driver
      .readPlanets()
      .execute()
      .flatMap { planets =>
        loop(planets.find(_.id == config.from).get, planets.find(_.id == config.to).get)
      }
  }

  def loop(from: PlayerPlanet, to: PlayerPlanet): Task[Unit] = {
    (for {
      ships <- driver.readFleetPage(from.id).map(_.ships)
      interestingShips = ships.filter(fs => config.ships.contains(fs._1)).filter(_._2 > 0)
      _ <- if (interestingShips.nonEmpty) {
        Logger[OgameAction].info(s"Found some interesting ships $interestingShips on planet. Sending them to target...") >>
          driver.sendFleet(
            SendFleetRequest(
              from,
              SendFleetRequestShips.Ships(interestingShips),
              to.coordinates,
              FleetMissionType.Deployment,
              FleetResources.Given(Resources.Zero)
            )
          )
      } else {
        ().pure[OgameAction]
      }
    } yield ()).execute()
  }
}

case class SendShipConfig(from: PlanetId, to: PlanetId, ships: List[ShipType], interval: FiniteDuration)
