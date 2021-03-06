package not.ogame.bots.ordon.action

import java.time.ZonedDateTime

import not.ogame.bots.FleetAttitude.Hostile
import not.ogame.bots.FleetMissionType.{Expedition, Spy}
import not.ogame.bots.ShipType._
import not.ogame.bots.ordon.core.{EventRegistry, OrdonOgameDriver, TimeBasedOrdonAction}
import not.ogame.bots.ordon.utils.SlackIntegration
import not.ogame.bots.{Fleet, MyFleetPageData, ShipType}

class StatusAction(expeditionFleet: Map[ShipType, Int]) extends TimeBasedOrdonAction {
  private val slackIntegration = new SlackIntegration()

  override def processTimeBased(ogame: OrdonOgameDriver, eventRegistry: EventRegistry): ZonedDateTime = {
    val allFleets = ogame.readAllFleets()
    val myFleetPageData = ogame.readMyFleets()
    val reports: Seq[String] = List(
      getExpeditionCountReport(myFleetPageData),
      getExpeditionCompositionReport(myFleetPageData),
      getFSReport(myFleetPageData),
      getHostileFleetReport(allFleets)
    )
    slackIntegration.postMessageToSlack(reports.mkString("\n"))
    if (reports.exists(report => report.startsWith("ERROR"))) {
      ZonedDateTime.now().plusMinutes(2)
    } else {
      ZonedDateTime.now().plusMinutes(10)
    }
  }

  private def getExpeditionCountReport(myFleetPageData: MyFleetPageData): String = {
    val currentExpeditions = myFleetPageData.fleetSlots.currentExpeditions
    val maxExpeditions = myFleetPageData.fleetSlots.maxExpeditions
    if (currentExpeditions + 1 >= maxExpeditions) {
      s"OK    Expedition fleet count $currentExpeditions/$maxExpeditions"
    } else {
      s"ERROR Expedition fleet count $currentExpeditions/$maxExpeditions"
    }
  }

  private def getExpeditionCompositionReport(myFleetPageData: MyFleetPageData): String = {
    val expectedFleetSize = expeditionFleet.values.sum
    val allShipsFine = myFleetPageData.fleets
      .filter(fleet => fleet.fleetMissionType == Expedition && !fleet.isReturning)
      .forall(fleet => {
        fleet.ships(LargeCargoShip) > 900 &&
          fleet.ships(LightFighter) > 1000 &&
          fleet.ships(Explorer) == 100 &&
          fleet.ships(EspionageProbe) == 1 &&
          fleet.ships(Destroyer) == 1
      })
    if (allShipsFine) {
      s"OK    Expedition composition ok"
    } else {
      s"ERROR Expedition composition mismatch"
    }
  }

  private def getFSReport(myFleetPageData: MyFleetPageData): String = {
    val maybeFS = myFleetPageData.fleets.find(_.ships(DeathStar) > 0)
    if (maybeFS.isDefined) {
      s"OK    FS fleet flying"
    } else {
      s"ERROR FS fleet down"
    }
  }

  private def getHostileFleetReport(allFleets: List[Fleet]): String = {
    val hostileFleets = allFleets.filter(fleet => fleet.fleetAttitude == Hostile && fleet.fleetMissionType != Spy)
    if (hostileFleets.isEmpty) {
      s"OK    No hostile fleets"
    } else {
      hostileFleets.foreach(
        fleet => slackIntegration.postAlertToSlack(s"Hostile fleet to:${fleet.to} is arriving on: ${fleet.arrivalTime}")
      )
      s"ERROR Hostile fleet detected"
    }
  }
}
