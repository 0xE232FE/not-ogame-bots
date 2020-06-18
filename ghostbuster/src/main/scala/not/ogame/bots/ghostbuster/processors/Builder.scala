package not.ogame.bots.ghostbuster.processors

import java.time.ZonedDateTime

import cats.implicits._
import eu.timepit.refined.numeric.Positive
import io.chrisdavenport.log4cats.Logger
import monix.eval.Task
import not.ogame.bots.facts.{FacilityBuildingCosts, ShipCosts, SuppliesBuildingCosts, TechnologyCosts}
import not.ogame.bots.ghostbuster.{FLogger, Wish}
import not.ogame.bots.selenium.refineVUnsafe
import not.ogame.bots._

class Builder(taskExecutor: TaskExecutor, wishlist: List[Wish]) extends FLogger {
  def buildNextThingFromWishList(planet: PlayerPlanet): Task[Option[ZonedDateTime]] = {
    val wishesForPlanet = wishlist.filter(_.planetId == planet.id)
    if (wishesForPlanet.nonEmpty) {
      for {
        sp <- taskExecutor.readSupplyPage(planet)
        fp <- taskExecutor.readFacilityPage(planet)
        tp <- taskExecutor.readTechnologyPage(planet)
        time <- buildNextThingFromWishList(planet, sp, fp, tp, wishesForPlanet)
      } yield time
    } else {
      Task.pure(None)
    }
  }

  private def buildNextThingFromWishList(
      planet: PlayerPlanet,
      suppliesPageData: SuppliesPageData,
      facilityPageData: FacilityPageData,
      technologyPageData: TechnologyPageData,
      wishesForPlanet: List[Wish]
  ) = {
    wishesForPlanet
      .collectFirst {
        case w: Wish.BuildSupply if suppliesPageData.getLevel(w.suppliesBuilding).value < w.level.value =>
          buildSupplyBuildingOrNothing(w.suppliesBuilding, suppliesPageData, planet)
        case w: Wish.BuildFacility if facilityPageData.getLevel(w.facilityBuilding).value < w.level.value =>
          buildFacilityBuildingOrNothing(w.facilityBuilding, facilityPageData, suppliesPageData, planet)
        case w: Wish.SmartSupplyBuilder if isSmartBuilderApplicable(planet, suppliesPageData, w) =>
          smartBuilder(planet, suppliesPageData, w)
        case w: Wish.BuildShip =>
          buildShips(planet, w, suppliesPageData)
        case w: Wish.Research if technologyPageData.getLevel(w.technology).value < w.level.value =>
          startResearch(planet, w.technology, technologyPageData)
      }
      .sequence
      .map(_.flatten)
  }

  private def startResearch(planet: PlayerPlanet, technology: Technology, technologyPageData: TechnologyPageData) = {
    if (technologyPageData.currentResearchProgress.isEmpty) {
      val level = nextLevel(technologyPageData, technology)
      val requiredResources = TechnologyCosts.technologyCost(technology, level)
      if (technologyPageData.currentResources.gtEqTo(requiredResources)) {
        taskExecutor.startResearch(planet, technology).map(Some(_))
      } else {
        Logger[Task]
          .info(
            s"Wanted to build $technology $level but there were not enough resources on ${planet.coordinates} " +
              s"- ${technologyPageData.currentResources}/$requiredResources"
          )
          .map(_ => None)
      }
    } else {
      Logger[Task]
        .info(s"Wanted to build $technology but there were some other research ongoing") >>
        technologyPageData.currentResearchProgress.map(_.finishTimestamp).pure[Task]
    }
  }

  private def buildShips(planet: PlayerPlanet, w: Wish.BuildShip, suppliesPageData: SuppliesPageData): Task[Option[ZonedDateTime]] = {
    val requiredResources = ShipCosts.shipCost(w.shipType).multiply(w.amount.value)
    if (suppliesPageData.currentResources.gtEqTo(requiredResources)) {
      taskExecutor.buildShip(w.shipType, w.amount.value, planet).map(_.some)
    } else {
      Logger[Task]
        .info(
          s"Wanted to build $w but there were not enough resources on ${planet.coordinates} " +
            s"- ${suppliesPageData.currentResources}/$requiredResources"
        )
        .map(_ => None) //TODO calculate resources availability
    }
  }

  private def buildSupplyBuildingOrNothing(suppliesBuilding: SuppliesBuilding, suppliesPageData: SuppliesPageData, planet: PlayerPlanet) = {
    if (!suppliesPageData.buildingInProgress) {
      val level = nextLevel(suppliesPageData, suppliesBuilding)
      val requiredResources = SuppliesBuildingCosts.buildingCost(suppliesBuilding, level)
      if (suppliesPageData.currentResources.gtEqTo(requiredResources)) {
        taskExecutor.buildSupplyBuilding(suppliesBuilding, level, planet).map(Some(_))
      } else {
        Logger[Task]
          .info(
            s"Wanted to build $suppliesBuilding $level but there were not enough resources on ${planet.coordinates} " +
              s"- ${suppliesPageData.currentResources}/$requiredResources"
          )
          .map(_ => None)
      }
    } else {
      Logger[Task]
        .info(s"Wanted to build $suppliesBuilding but something was being built") >>
        suppliesPageData.currentBuildingProgress.map(_.finishTimestamp).pure[Task]
    }
  }

  private def buildFacilityBuildingOrNothing(
      facilityBuilding: FacilityBuilding,
      facilityPageData: FacilityPageData,
      suppliesPageData: SuppliesPageData,
      planet: PlayerPlanet
  ) = {
    if (!suppliesPageData.shipInProgress) {
      val level = nextLevel(facilityPageData, facilityBuilding)
      val requiredResources = FacilityBuildingCosts.buildingCost(facilityBuilding, level)
      if (facilityPageData.currentResources.gtEqTo(requiredResources)) {
        taskExecutor.buildFacilityBuilding(facilityBuilding, level, planet).map(Some(_))
      } else {
        Logger[Task]
          .info(
            s"Wanted to build $facilityBuilding $level but there were not enough resources on ${planet.coordinates}" +
              s"- ${suppliesPageData.currentResources}/$requiredResources"
          )
          .map(_ => None)
      }
    } else {
      Logger[Task]
        .info(s"Wanted to build $facilityBuilding but there were some ships building") >>
        suppliesPageData.currentBuildingProgress.map(_.finishTimestamp).pure[Task]
    }
  }

  private def nextLevel(suppliesPage: SuppliesPageData, building: SuppliesBuilding) = {
    refineVUnsafe[Positive, Int](suppliesPage.suppliesLevels.values(building).value + 1)
  }

  private def nextLevel(technologyPageData: TechnologyPageData, technology: Technology) = {
    refineVUnsafe[Positive, Int](technologyPageData.technologyLevels.values(technology).value + 1)
  }

  private def nextLevel(facilityPageData: FacilityPageData, building: FacilityBuilding) = {
    refineVUnsafe[Positive, Int](facilityPageData.facilityLevels.values(building).value + 1)
  }

  private def smartBuilder(planet: PlayerPlanet, suppliesPageData: SuppliesPageData, w: Wish.SmartSupplyBuilder) = {
    if (!suppliesPageData.buildingInProgress) {
      if (suppliesPageData.currentResources.energy < 0) {
        buildBuildingOrStorage(planet, suppliesPageData, SuppliesBuilding.SolarPlant)
      } else { //TODO can we get rid of hardcoded ratio?
        val shouldBuildDeuter = suppliesPageData.getLevel(SuppliesBuilding.MetalMine).value -
          suppliesPageData.getLevel(SuppliesBuilding.DeuteriumSynthesizer).value > 2 &&
          suppliesPageData.getLevel(SuppliesBuilding.DeuteriumSynthesizer).value < w.deuterLevel.value
        val shouldBuildCrystal = suppliesPageData.getLevel(SuppliesBuilding.MetalMine).value -
          suppliesPageData.getLevel(SuppliesBuilding.CrystalMine).value > 2 &&
          suppliesPageData.getLevel(SuppliesBuilding.CrystalMine).value < w.crystalLevel.value
        if (shouldBuildDeuter) {
          buildBuildingOrStorage(planet, suppliesPageData, SuppliesBuilding.DeuteriumSynthesizer)
        } else if (shouldBuildCrystal) {
          buildBuildingOrStorage(planet, suppliesPageData, SuppliesBuilding.CrystalMine)
        } else if (suppliesPageData.getLevel(SuppliesBuilding.MetalMine).value < w.metalLevel.value) {
          buildBuildingOrStorage(planet, suppliesPageData, SuppliesBuilding.MetalMine)
        } else {
          Option.empty[ZonedDateTime].pure[Task]
        }
      }
    } else {
      Logger[Task]
        .info(s"Wanted to run smart builder but sth was being built") >>
        suppliesPageData.currentBuildingProgress.map(_.finishTimestamp).pure[Task]
    }
  }

  private def buildBuildingOrStorage(planet: PlayerPlanet, suppliesPageData: SuppliesPageData, building: SuppliesBuilding) = {
    val level = nextLevel(suppliesPageData, building)
    val requiredResources = SuppliesBuildingCosts.buildingCost(building, level)
    if (suppliesPageData.currentCapacity.gtEqTo(requiredResources)) {
      buildSupplyBuildingOrNothing(building, suppliesPageData, planet)
    } else {
      buildStorage(suppliesPageData, requiredResources, planet)
    }
  }

  private def buildStorage(
      suppliesPage: SuppliesPageData,
      requiredResources: Resources,
      planet: PlayerPlanet
  ): Task[Option[ZonedDateTime]] = {
    requiredResources.difference(suppliesPage.currentCapacity) match {
      case Resources(m, _, _, _) if m > 0 =>
        buildSupplyBuildingOrNothing(SuppliesBuilding.MetalStorage, suppliesPage, planet)
      case Resources(_, c, _, _) if c > 0 =>
        buildSupplyBuildingOrNothing(SuppliesBuilding.CrystalStorage, suppliesPage, planet)
      case Resources(_, _, d, _) if d > 0 =>
        buildSupplyBuildingOrNothing(SuppliesBuilding.DeuteriumStorage, suppliesPage, planet)
    }
  }

  private def isSmartBuilderApplicable(planet: PlayerPlanet, suppliesPageData: SuppliesPageData, w: Wish.SmartSupplyBuilder) = {
    val correctPlanet = w.planetId == planet.id
    val metalMineUnderLevel = w.metalLevel.value > suppliesPageData.getLevel(SuppliesBuilding.MetalMine).value
    val crystalMineUnderLevel = w.crystalLevel.value > suppliesPageData.getLevel(SuppliesBuilding.CrystalMine).value
    val deuterMineUnderLevel = w.deuterLevel.value > suppliesPageData.getLevel(SuppliesBuilding.DeuteriumSynthesizer).value
    correctPlanet && (metalMineUnderLevel || crystalMineUnderLevel || deuterMineUnderLevel)
  }
}
