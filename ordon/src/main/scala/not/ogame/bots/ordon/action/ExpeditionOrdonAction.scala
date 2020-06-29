package not.ogame.bots.ordon.action

import java.time.ZonedDateTime

import not.ogame.bots.CoordinatesType.Planet
import not.ogame.bots.FleetMissionType.Expedition
import not.ogame.bots._
import not.ogame.bots.ordon.core.{OrdonOgameDriver, TimeBasedOrdonAction}
import not.ogame.bots.ordon.utils.{SelectResources, SelectShips, SendFleet}

class ExpeditionOrdonAction(val startPlanet: PlayerPlanet, val expeditionFleet: Map[ShipType, Int]) extends TimeBasedOrdonAction {
  private val coordinates: Coordinates = startPlanet.coordinates.copy(position = 16, coordinatesType = Planet)
  private val selectShips: SelectShips = page => page.ships.map(e => e._1 -> Math.min(e._2, expeditionFleet.getOrElse(e._1, 0)))
  private val selectResources: SelectResources = _ => Resources.Zero
  val sendFleetHelper = new SendFleet(
    from = startPlanet,
    to = PlayerPlanet(PlanetId.apply(""), coordinates),
    selectShips = selectShips,
    selectResources = selectResources,
    missionType = Expedition,
    fleetSpeed = FleetSpeed.Percent100
  )

  override def processTimeBased(ogame: OrdonOgameDriver): ZonedDateTime = {
    val page = ogame.readMyFleets()
    if (page.fleetSlots.currentExpeditions >= page.fleetSlots.maxExpeditions) {
      page.fleets.filter(_.fleetMissionType == Expedition).map(_.arrivalTime).min
    } else {
      ogame.sendFleet(sendFleetHelper.getSendFleetRequest(ogame))
      processTimeBased(ogame)
    }
  }
}