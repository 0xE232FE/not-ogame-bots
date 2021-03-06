package not.ogame.bots.facts

import not.ogame.bots.{FacilityBuilding, Resources}

object FacilityBuildingCosts {
  def buildingCost(facilityBuilding: FacilityBuilding, level: Int): Resources = {
    facilityBuilding match {
      case FacilityBuilding.RoboticsFactory => fromBaseCostPowerOf2(Resources(400, 120, 200), level)
      case FacilityBuilding.Shipyard        => fromBaseCostPowerOf2(Resources(400, 200, 100), level)
      case FacilityBuilding.ResearchLab     => fromBaseCostPowerOf2(Resources(200, 400, 200), level)
      case FacilityBuilding.NaniteFactory   => fromBaseCostPowerOf2(Resources(1_000_000, 500_000, 100_000), level)
    }
  }

  private def fromBaseCostPowerOf2(baseCost: Resources, level: Int): Resources = {
    Resources(
      metal = (baseCost.metal * 2.0.pow(level - 1.0)).toInt,
      crystal = (baseCost.crystal * 2.0.pow(level - 1.0)).toInt,
      deuterium = (baseCost.deuterium * 2.0.pow(level - 1.0)).toInt
    )
  }
}
