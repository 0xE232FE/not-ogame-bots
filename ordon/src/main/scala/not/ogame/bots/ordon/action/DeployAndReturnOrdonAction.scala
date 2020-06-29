package not.ogame.bots.ordon.action

import java.time.ZonedDateTime

import not.ogame.bots.FleetMissionType.Deployment
import not.ogame.bots.ShipType.LargeCargoShip
import not.ogame.bots.ordon.core.{OrdonOgameDriver, TimeBasedOrdonAction}
import not.ogame.bots.ordon.utils.{FleetSelector, ResourceSelector, Selector, SendFleet}
import not.ogame.bots.{FleetSpeed, MyFleet, PlayerPlanet}

import scala.util.Random

class DeployAndReturnOrdonAction(planet: PlayerPlanet, moon: PlayerPlanet) extends TimeBasedOrdonAction {
  private val safeBufferInMinutes: Int = 10
  private val randomUpperLimitInSeconds: Int = 240
  private val sendFleet = new SendFleet(
    from = planet,
    to = moon,
    selectShips = new FleetSelector(filters = Map(LargeCargoShip -> Selector.skip)),
    selectResources = new ResourceSelector(deuteriumSelector = Selector.decreaseBy(300_000)),
    fleetSpeed = FleetSpeed.Percent10
  )

  override def processTimeBased(ogame: OrdonOgameDriver): ZonedDateTime = {
    val maybeThisFleet = findThisFleet(ogame)
    if (maybeThisFleet.isDefined) {
      val thisFleet = maybeThisFleet.get
      if (thisFleet.isReturning) {
        handleReturningFleet(thisFleet)
      } else {
        handleFlyingFleet(ogame, thisFleet)
      }
    } else {
      handleFleetNotFound(ogame)
    }
  }

  private def handleFlyingFleet(ogame: OrdonOgameDriver, fleet: MyFleet): ZonedDateTime = {
    if (isCloseToArrival(fleet)) {
      ogame.returnFleet(fleet.fleetId)
      handleReturningFleet(findThisFleet(ogame).get)
    } else {
      chooseTimeWhenClickReturn(fleet)
    }
  }

  private def handleReturningFleet(fleet: MyFleet): ZonedDateTime = {
    fleet.arrivalTime.plusSeconds(3)
  }

  private def handleFleetNotFound(ogame: OrdonOgameDriver): ZonedDateTime = {
    ogame.sendFleet(sendFleet.getSendFleetRequest(ogame))
    chooseTimeWhenClickReturn(findThisFleet(ogame).get)
  }

  private def isCloseToArrival(fleet: MyFleet) = {
    fleet.arrivalTime.minusMinutes(safeBufferInMinutes).minusSeconds(randomUpperLimitInSeconds).isBefore(ZonedDateTime.now())
  }

  private def chooseTimeWhenClickReturn(fleet: MyFleet): ZonedDateTime = {
    fleet.arrivalTime.minusMinutes(safeBufferInMinutes).minusSeconds(Random.nextLong(randomUpperLimitInSeconds))
  }

  private def findThisFleet(ogame: OrdonOgameDriver): Option[MyFleet] = {
    ogame.readMyFleets().fleets.find(isThisFleet)
  }

  private def isThisFleet(fleet: MyFleet): Boolean = {
    fleet.from == planet.coordinates && fleet.to == moon.coordinates && fleet.fleetMissionType == Deployment && fleet.ships(LargeCargoShip) == 0
  }
}