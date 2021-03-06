package not.ogame.bots.ordon

import java.time.ZonedDateTime

import cats.Monad
import cats.implicits._
import not.ogame.bots.SuppliesBuilding.SolarPlant
import not.ogame.bots._
import not.ogame.bots.facts.{FacilityBuildingCosts, SuppliesBuildingCosts, TechnologyCosts}

class BuildBuildingsOgameAction[T[_]: Monad](planet: PlayerPlanet, tasks: List[TaskOnPlanet])(implicit clock: LocalClock)
    extends SimpleOgameAction[T] {
  override def processSimple(ogame: OgameDriver[T]): T[ZonedDateTime] =
    for {
      suppliesPage <- ogame.readSuppliesPage(planet.id)
      facilityPage <- ogame.readFacilityPage(planet.id)
      technologyPage <- ogame.readTechnologyPage(planet.id)
      nextTask = getNextTask(suppliesPage, facilityPage, technologyPage)
      resumeOn <- buildOrWaitForResources(ogame, suppliesPage, facilityPage, technologyPage, nextTask)
    } yield resumeOn

  def isAnyPositive(resources: Resources): Boolean = {
    resources.metal > 0 || resources.crystal > 0 || resources.deuterium > 0
  }

  private def buildOrWaitForResources(
      ogame: OgameDriver[T],
      suppliesPage: SuppliesPageData,
      facilityPage: FacilityPageData,
      technologyPage: TechnologyPageData,
      task: TaskOnPlanet
  ): T[ZonedDateTime] = {
    val maybeTimeToFree = task.isBusy(suppliesPage, facilityPage, technologyPage)
    if (maybeTimeToFree.isDefined) {
      return maybeTimeToFree.get.pure[T]
    }
    val cost = task.cost()
    val currentResources = suppliesPage.currentResources
    val deficit = minus(cost, currentResources)
    if (isAnyPositive(deficit)) {
      var maxTimeInHours = 0.0
      if (deficit.metal > 0) {
        if (suppliesPage.currentProduction.metal > 0) {
          maxTimeInHours = Math.max(maxTimeInHours, deficit.metal.toDouble / suppliesPage.currentProduction.metal)
        }
      }
      if (deficit.crystal > 0) {
        if (suppliesPage.currentProduction.crystal > 0) {
          maxTimeInHours = Math.max(maxTimeInHours, deficit.crystal.toDouble / suppliesPage.currentProduction.crystal)
        }
      }
      if (deficit.deuterium > 0) {
        if (suppliesPage.currentProduction.deuterium > 0) {
          maxTimeInHours = Math.max(maxTimeInHours, deficit.deuterium.toDouble / suppliesPage.currentProduction.deuterium)
        }
      }
      val maxTimeInSeconds = maxTimeInHours * 3600
      clock.now().plusSeconds(maxTimeInSeconds.toInt).pure[T]
    } else {
      task.construct(ogame, planet)
    }
  }

  private def minus(one: Resources, other: Resources): Resources = {
    Resources(one.metal - other.metal, one.crystal - other.crystal, one.deuterium - other.deuterium)
  }

  private def getNextTask(
      suppliesPage: SuppliesPageData,
      facilityPage: FacilityPageData,
      technologyPage: TechnologyPageData
  ): TaskOnPlanet = {
    if (suppliesPage.currentResources.energy < 0) {
      if (suppliesPage.getIntLevel(SolarPlant) >= 16) {
        new SolarSatelliteBuildingTask(suppliesPage.currentResources.energy / 35 + 1)
      } else {
        new SuppliesBuildingTask(SolarPlant, suppliesPage.getIntLevel(SolarPlant) + 1)
      }
    } else {
      tasks.find(p => p.isValid(suppliesPage, facilityPage, technologyPage)).get
    }
  }

  override def toString: String = super.toString + planet.coordinates
}

class SuppliesBuildingTask(suppliesBuilding: SuppliesBuilding, level: Int)(implicit clock: LocalClock) extends TaskOnPlanet {
  override def isValid(suppliesPage: SuppliesPageData, facilityPage: FacilityPageData, technologyPage: TechnologyPageData): Boolean = {
    suppliesPage.getIntLevel(suppliesBuilding) < level
  }

  override def cost(): Resources = SuppliesBuildingCosts.buildingCost(suppliesBuilding, level)

  override def construct[T[_]: Monad](ogameDriver: OgameDriver[T], planet: PlayerPlanet): T[ZonedDateTime] =
    for {
      _ <- ogameDriver.buildSuppliesBuilding(planet.id, suppliesBuilding)
      page <- ogameDriver.readSuppliesPage(planet.id)
      resumeOn = page.currentBuildingProgress.map(_.finishTimestamp).getOrElse(clock.now())
    } yield resumeOn

  override def isBusy(
      suppliesPage: SuppliesPageData,
      facilityPage: FacilityPageData,
      technologyPage: TechnologyPageData
  ): Option[ZonedDateTime] = {
    suppliesPage.currentBuildingProgress.map(_.finishTimestamp)
  }
}

class FacilityBuildingTask(facilityBuilding: FacilityBuilding, level: Int)(implicit clock: LocalClock) extends TaskOnPlanet {
  override def isValid(suppliesPage: SuppliesPageData, facilityPage: FacilityPageData, technologyPage: TechnologyPageData): Boolean = {
    facilityPage.getIntLevel(facilityBuilding) < level
  }

  override def cost(): Resources = FacilityBuildingCosts.buildingCost(facilityBuilding, level)

  override def construct[T[_]: Monad](ogameDriver: OgameDriver[T], planet: PlayerPlanet): T[ZonedDateTime] =
    for {
      _ <- ogameDriver.buildFacilityBuilding(planet.id, facilityBuilding)
      page <- ogameDriver.readSuppliesPage(planet.id)
      resumeOn = page.currentBuildingProgress.map(_.finishTimestamp).getOrElse(clock.now())
    } yield resumeOn

  override def isBusy(
      suppliesPage: SuppliesPageData,
      facilityPage: FacilityPageData,
      technologyPage: TechnologyPageData
  ): Option[ZonedDateTime] = {
    facilityPage.currentBuildingProgress.map(_.finishTimestamp)
  }
}

class TechnologyBuildingTask(technology: Technology, level: Int)(implicit clock: LocalClock) extends TaskOnPlanet {
  override def isValid(suppliesPage: SuppliesPageData, facilityPage: FacilityPageData, technologyPage: TechnologyPageData): Boolean = {
    technologyPage.getIntLevel(technology) < level
  }

  override def cost(): Resources = TechnologyCosts.technologyCost(technology, level)

  override def construct[T[_]: Monad](ogameDriver: OgameDriver[T], planet: PlayerPlanet): T[ZonedDateTime] =
    for {
      _ <- ogameDriver.startResearch(planet.id, technology)
      page <- ogameDriver.readTechnologyPage(planet.id)
      resumeOn = page.currentResearchProgress.map(_.finishTimestamp).getOrElse(clock.now())
    } yield resumeOn

  override def isBusy(
      suppliesPage: SuppliesPageData,
      facilityPage: FacilityPageData,
      technologyPage: TechnologyPageData
  ): Option[ZonedDateTime] = {
    technologyPage.currentResearchProgress.map(_.finishTimestamp)
  }
}

class SolarSatelliteBuildingTask(count: Int) extends TaskOnPlanet {
  override def isBusy(
      suppliesPage: SuppliesPageData,
      facilityPage: FacilityPageData,
      technologyPage: TechnologyPageData
  ): Option[ZonedDateTime] = {
    suppliesPage.currentBuildingProgress
      .map(_.finishTimestamp)
      .flatMap(one => suppliesPage.currentShipyardProgress.map(_.finishTimestamp).map(other => List(one, other).max))
      .orElse(suppliesPage.currentShipyardProgress.map(_.finishTimestamp))
  }

  override def isValid(suppliesPage: SuppliesPageData, facilityPage: FacilityPageData, technologyPage: TechnologyPageData): Boolean = ???

  override def cost(): Resources = Resources(0, 2_000 * count, 500 * count)

  override def construct[T[_]: Monad](ogameDriver: OgameDriver[T], planet: PlayerPlanet): T[ZonedDateTime] =
    for {
      _ <- ogameDriver.buildSolarSatellites(planet.id, count)
      page <- ogameDriver.readSuppliesPage(planet.id)
      resumeOn = page.currentShipyardProgress.get.finishTimestamp
    } yield resumeOn
}

trait TaskOnPlanet {
  def isBusy(suppliesPage: SuppliesPageData, facilityPage: FacilityPageData, technologyPage: TechnologyPageData): Option[ZonedDateTime]

  def isValid(suppliesPage: SuppliesPageData, facilityPage: FacilityPageData, technologyPage: TechnologyPageData): Boolean

  def cost(): Resources

  def construct[T[_]: Monad](ogameDriver: OgameDriver[T], planet: PlayerPlanet): T[ZonedDateTime]
}
