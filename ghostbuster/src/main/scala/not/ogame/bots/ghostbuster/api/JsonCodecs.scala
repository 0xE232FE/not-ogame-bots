package not.ogame.bots.ghostbuster.api

import io.circe.{Decoder, Encoder, HCursor, Json, KeyDecoder, KeyEncoder}
import not.ogame.bots.{Coordinates, CoordinatesType, FacilityBuilding, FleetAttitude, FleetMissionType, ShipType, SuppliesBuilding}
import com.softwaremill.tagging._
import io.circe.generic.AutoDerivation

trait JsonCodecs extends AutoDerivation {
  implicit val coordinatesTypeEncoder: Encoder[CoordinatesType] = (a: CoordinatesType) => Json.fromString(a.entryName)
  implicit val coordinatesTypeDecoder: Decoder[CoordinatesType] = (c: HCursor) =>
    c.as[String].map { text =>
      CoordinatesType.values.collectFirst { case v if v.entryName == text => v }.get
    }

  implicit val fleetMissionTypeEncoder: Encoder[FleetMissionType] = (a: FleetMissionType) => Json.fromString(a.entryName)
  implicit val fleetMissionTypeDecoder: Decoder[FleetMissionType] = (c: HCursor) =>
    c.as[String].map { text =>
      FleetMissionType.values.collectFirst { case v if v.entryName == text => v }.get
    }

  implicit val fleetAttitudeEncoder: Encoder[FleetAttitude] = (a: FleetAttitude) => Json.fromString(a.entryName)
  implicit val fleetAttitudeDecoder: Decoder[FleetAttitude] = (c: HCursor) =>
    c.as[String].map { text =>
      FleetAttitude.values.collectFirst { case v if v.entryName == text => v }.get
    }

  implicit val shipTypeKeyEncoder: KeyEncoder[ShipType] = (key: ShipType) => key.entryName
  implicit val shipTypeKeyDecoder: KeyDecoder[ShipType] = (key: String) =>
    ShipType.values.collectFirst { case v if v.entryName == key => v }

  implicit val facilityBuildingKeyEncoder: KeyEncoder[FacilityBuilding] = (key: FacilityBuilding) => key.entryName
  implicit val facilityBuildingKeyDecoder: KeyDecoder[FacilityBuilding] = (key: String) =>
    FacilityBuilding.values.collectFirst { case v if v.entryName == key => v }

  implicit val suppliesBuildingKeyEncoder: KeyEncoder[SuppliesBuilding] = (key: SuppliesBuilding) => key.entryName
  implicit val suppliesBuildingKeyDecoder: KeyDecoder[SuppliesBuilding] = (key: String) =>
    SuppliesBuilding.values.collectFirst { case v if v.entryName == key => v }

  implicit val coordsEncoderKeyEncoder: KeyEncoder[Coordinates] = (key: Coordinates) => s"${key.galaxy}:${key.system}:${key.position}"
  implicit val coordsDecoderKeyDecoder: KeyDecoder[Coordinates] = (key: String) => {
    key.split(":").toList match {
      case a :: b :: c :: Nil => Some(Coordinates(a.toInt, b.toInt, c.toInt))
      case _                  => None
    }
  }

  implicit def taggedStringKeyEncoder[U]: KeyEncoder[String @@ U] = (key: String) => key
  implicit def taggedStringKeyDecoder[U]: KeyDecoder[String @@ U] = (key: String) => Some(key.taggedWith[U])

  implicit def taggedStringEncoder[U]: Encoder[String @@ U] = Encoder.encodeString.contramap[String @@ U](identity)
  implicit def taggedStringDecoder[U]: Decoder[String @@ U] = Decoder.decodeString.map(s => s.taggedWith[U])
}
