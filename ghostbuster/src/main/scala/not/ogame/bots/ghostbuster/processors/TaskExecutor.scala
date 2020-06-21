package not.ogame.bots.ghostbuster.processors

import java.time.ZonedDateTime

import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import monix.eval.Task
import monix.reactive.Observable
import not.ogame.bots._
import not.ogame.bots.ghostbuster.PlanetFleet
import not.ogame.bots.ghostbuster.executor.Notification

trait TaskExecutor {
  def readMyFleets(): Task[MyFleetPageData]

  def returnFleet(fleetId: FleetId): Task[ZonedDateTime]

  def buildShip(shipType: ShipType, amount: Int, head: PlayerPlanet): Task[ZonedDateTime]

  def waitTo(instant: ZonedDateTime): Task[Unit]

  def readAllFleets(): Task[List[Fleet]]

  def readPlanets(): Task[List[PlayerPlanet]] = readPlanetsAndMoons().map(_.filter(_.coordinates.coordinatesType != CoordinatesType.Moon))

  def readPlanetsAndMoons(): Task[List[PlayerPlanet]]

  def sendFleet(req: SendFleetRequest): Task[Unit]

  def sendAndTrackFleet(request: SendFleetRequest): Task[MyFleet]

  def getFleetOnPlanet(planet: PlayerPlanet): Task[PlanetFleet]

  def readSupplyPage(playerPlanet: PlayerPlanet): Task[SuppliesPageData]

  def readFacilityPage(playerPlanet: PlayerPlanet): Task[FacilityPageData]

  def readTechnologyPage(playerPlanet: PlayerPlanet): Task[TechnologyPageData]

  def startResearch(playerPlanet: PlayerPlanet, technology: Technology): Task[ZonedDateTime]

  def buildSupplyBuilding(suppliesBuilding: SuppliesBuilding, level: Int Refined Positive, planet: PlayerPlanet): Task[ZonedDateTime]

  def buildFacilityBuilding(facilityBuilding: FacilityBuilding, level: Int Refined Positive, planet: PlayerPlanet): Task[ZonedDateTime]

  def subscribeToNotifications: Observable[Notification]
}
