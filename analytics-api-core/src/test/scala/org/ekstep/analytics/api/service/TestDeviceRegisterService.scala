package org.ekstep.analytics.api.service

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestActorRef, TestProbe}
import com.typesafe.config.Config
import org.ekstep.analytics.api.BaseSpec
import org.ekstep.analytics.api.util._
import org.ekstep.analytics.framework.util.JSONUtils
import org.mockito.Mockito._
import redis.clients.jedis.Jedis

import scala.concurrent.ExecutionContext

class TestDeviceRegisterService extends BaseSpec {

  val deviceRegisterServiceMock: DeviceRegisterService = mock[DeviceRegisterService]
  private implicit val system: ActorSystem = ActorSystem("device-register-test-actor-system", config)
  private val configMock = mock[Config]
  private val jedisMock = mock[Jedis]
  private val redisUtilMock = mock[RedisUtil]
  private val postgresDBMock = mock[PostgresDBUtil]
  private val H2DBMock = mock[H2DBUtil]
  implicit val executor: ExecutionContext = scala.concurrent.ExecutionContext.global
  val redisIndex: Int = config.getInt("redis.deviceIndex")
  val saveMetricsActor = TestActorRef(new SaveMetricsActor)
  val metricsActorProbe = TestProbe()

  when(configMock.getInt("redis.deviceIndex")).thenReturn(redisIndex)
  when(configMock.getString("postgres.table.geo_location_city.name")).thenReturn("geo_location_city")
  when(configMock.getString("postgres.table.geo_location_city_ipv4.name")).thenReturn("geo_location_city_ipv4")
  when(configMock.getBoolean("device.api.enable.debug.log")).thenReturn(true)
  private val deviceRegisterService = TestActorRef(new DeviceRegisterService(saveMetricsActor, configMock, redisUtilMock, postgresDBMock, H2DBMock)).underlyingActor
  private val deviceRegisterActorRef = TestActorRef(new DeviceRegisterService(saveMetricsActor, configMock, redisUtilMock, postgresDBMock, H2DBMock) {
    override val metricsActor: ActorRef = metricsActorProbe.ref
  })

  private val geoLocationCityIpv4TableName = config.getString("postgres.table.geo_location_city_ipv4.name")
  private val geoLocationCityTableName = config.getString("postgres.table.geo_location_city.name")

  val request: String =
    s"""
       |{"id":"analytics.device.register",
       |"ver":"1.0","ts":"2016-09-12T18:43:23.890+00:00",
       |"params":{"msgid":"4f04da60-1e24-4d31-aa7b-1daf91c46341"},
       |"request":{"channel":"test-channel",
       |"dspec":{"cpu":"abi:  armeabi-v7a  ARMv7 Processor rev 4 (v7l)","make":"Micromax Micromax A065","os":"Android 4.4.2"}}}
       |""".stripMargin

  val successResponse: String =
    s"""
       |{
       |  "id":"analytics.device-register",
       |  "ver":"1.0",
       |  "ts":"2018-11-08T10:16:27.512+00:00",
       |  "params":{
       |    "resmsgid":"79594dd2-ad13-44fd-8797-8a05ca5cac7b",
       |    "status":"successful",
       |    "client_key":null
       |  },
       |  "responseCode":"OK",
       |  "result":{
       |    "message":"Device registered successfully"
       |  }
       |}
     """.stripMargin

  override def beforeAll() {
    super.beforeAll()
    when(redisUtilMock.getConnection(redisIndex)).thenReturn(jedisMock)
  }

  val uaspec = s"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_13_6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/70.0.3538.77 Safari/537.36"


  "Device register request " should "generate data for logging device register request" in {

    val deviceLocation = DeviceLocation("Asia", "IN", "India", "KA", "Karnataka", "", "Bangalore", "KARNATAKA", "29", "BANGALORE")
    val deviceId = "test-device-1"
    val deviceSpec = JSONUtils.deserialize[Map[String, AnyRef]]("{\"cpu\":\"abi:  armeabi-v7a  ARMv7 Processor rev 4 (v7l)\",\"make\":\"Micromax Micromax A065\",\"os\":\"Android 4.4.2\"}")
    val producerId = Some("prod.diksha.app")
    val fcmToken = Some("test-token")

    when(configMock.getInt("metrics.time.interval.min")).thenReturn(300)
    when(configMock.getString("postgres.table.geo_location_city.name")).thenReturn("geo_location_city.name")
    when(configMock.getString("postgres.table.geo_location_city_ipv4.name")).thenReturn("geo_location_city_ipv4.name")

    val deviceProfileLog = DeviceProfileLog(device_id = deviceId, location = deviceLocation, producer_id = producerId,
      device_spec = Some(deviceSpec), uaspec = Some(uaspec), fcm_token = fcmToken)
    val log = deviceRegisterService.generateDeviceRegistrationLogEvent(deviceProfileLog)
    val outputMap = JSONUtils.deserialize[Map[String, AnyRef]](log)

    val listOfExpectedKeys = List("device_id", "city", "country", "country_code", "state_code_custom", "state_code", "state", "state_custom",
      "district_custom", "first_access", "device_spec", "uaspec", "api_last_updated_on", "producer_id", "fcm_token")
    val diff = outputMap.keySet.diff(listOfExpectedKeys.toSet)

    // All the input keys should be written to log
    diff.size should be(0)
  }


  "Optional fields in request" should " be skipped from the log" in {

    val deviceLocation = DeviceLocation("Asia", "IN", "India", "KA", "Karnataka", "", "Bangalore", "KARNATAKA", "29", "BANGALORE")
    val deviceId = "test-device-1"
    val deviceSpec = JSONUtils.deserialize[Map[String, AnyRef]]("{\"cpu\":\"abi:  armeabi-v7a  ARMv7 Processor rev 4 (v7l)\",\"make\":\"Micromax Micromax A065\",\"os\":\"Android 4.4.2\"}")

    when(configMock.getInt("metrics.time.interval.min")).thenReturn(300)
    when(configMock.getString("postgres.table.geo_location_city.name")).thenReturn("geo_location_city.name")
    when(configMock.getString("postgres.table.geo_location_city_ipv4.name")).thenReturn("geo_location_city_ipv4.name")

    val deviceProfileLog = DeviceProfileLog(device_id = deviceId, location = deviceLocation, device_spec = Some(deviceSpec), uaspec = Some(uaspec))
    val log = deviceRegisterService.generateDeviceRegistrationLogEvent(deviceProfileLog)
    val outputMap = JSONUtils.deserialize[Map[String, AnyRef]](log)
    // All the optional keys not present in the input request should not be written to log
    outputMap.contains("fcm_token") should be(false)
    outputMap.contains("producer_id") should be(false)
    outputMap.contains("first_access") should be(true) // uses current time by default
  }

  "Resolve location" should "return location details given an IP address" in {
    when(deviceRegisterServiceMock.resolveLocation(ipAddress = "106.51.74.185"))
      .thenReturn(DeviceLocation("Asia", "IN", "India", "KA", "Karnataka", "", "Bangalore", "KARNATAKA", "29", "BANGALORE"))
    val deviceLocation = deviceRegisterServiceMock.resolveLocation("106.51.74.185")
    deviceLocation.countryCode should be("IN")
    deviceLocation.countryName should be("India")
    deviceLocation.stateCode should be("KA")
    deviceLocation.state should be("Karnataka")
    deviceLocation.city should be("Bangalore")
    deviceLocation.stateCustom should be("KARNATAKA")
    deviceLocation.stateCodeCustom should be("29")
    deviceLocation.districtCustom should be("BANGALORE")
  }

  "Resolve location" should "return empty location if the IP address is not found" in {
    when(deviceRegisterServiceMock.resolveLocation(ipAddress = "106.51.74.185"))
      .thenReturn(new DeviceLocation())
    val deviceLocation = deviceRegisterServiceMock.resolveLocation("106.51.74.185")
    deviceLocation.countryCode should be("")
    deviceLocation.countryName should be("")
    deviceLocation.stateCode should be("")
    deviceLocation.state should be("")
    deviceLocation.city should be("")
    deviceLocation.stateCustom should be("")
    deviceLocation.stateCodeCustom should be("")
    deviceLocation.districtCustom should be("")
  }

  "When User-Agent is empty" should "return empty string for user agent map" in {
    when(deviceRegisterServiceMock.parseUserAgent(None)).thenReturn(None)
    val uaspecResult: Option[String] = deviceRegisterServiceMock.parseUserAgent(None)
    uaspecResult should be(None)
  }

  "register device message" should "resolve location write to logger" in {
    val deviceSpec = "{\"cpu\":\"abi:  armeabi-v7a  ARMv7 Processor rev 4 (v7l)\",\"make\":\"Micromax Micromax A065\",\"os\":\"Android 4.4.2\"}"
    val uaspec = s"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_13_6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/70.0.3538.77 Safari/537.36"

    val query =
      s"""
         |SELECT
         |  glc.continent_name,
         |  glc.country_iso_code country_code,
         |  glc.country_name,
         |  glc.subdivision_1_iso_code state_code,
         |  glc.subdivision_1_name state,
         |  glc.subdivision_2_name sub_div_2,
         |  glc.city_name city,
         |  glc.subdivision_1_custom_name state_custom,
         |  glc.subdivision_1_custom_code state_code_custom,
         |  glc.subdivision_2_custom_name district_custom
         |FROM $geoLocationCityIpv4TableName gip,
         |  $geoLocationCityTableName glc
         |WHERE glc.country_iso_code = 'IN'
         |  AND gip.geoname_id = glc.geoname_id
         |  AND gip.network_start_integer <= 1935923652
         |  AND gip.network_last_integer >= 1935923652
               """.stripMargin

    when(postgresDBMock.readLocation(query)).thenReturn(List(DeviceLocation(continentName = "Asia", countryCode = "IN", countryName = "India", stateCode = "KA",
      state = "TamilNadu", subDivsion2 = null, city = "chennai",
      stateCustom = "chennai", stateCodeCustom = "29", districtCustom = null)))

    deviceRegisterActorRef.tell(RegisterDevice(did = "device-001", headerIP = "115.99.217.196", ip_addr = Option("115.99.217.196"), fcmToken = Option("some-token"), producer = Option("prod.diksha.app"), dspec = Option(deviceSpec), uaspec = Option(uaspec), first_access = Option(123456789), user_declared_state = Option("TamilNadu"), user_declared_district = Option("chennai")), ActorRef.noSender)
    verify(postgresDBMock, times(1)).readLocation(query)

    metricsActorProbe.expectMsg(IncrementApiCalls)
    metricsActorProbe.expectMsg(IncrementLocationDbHitCount)
    metricsActorProbe.expectMsg(IncrementLocationDbSuccessCount)
    metricsActorProbe.expectMsg(IncrementLogDeviceRegisterSuccessCount)

  }

  "Device Register" should "get device profile map which will be saved to redis" in {
    val register = RegisterDevice("test-device", "106.51.74.185", None, None, None, Option(""), None, None, Option("Karnataka"), Option("BANGALORE"))
    val location = new DeviceLocation()
    val dataMap = Map("device_id" -> "test-device", "devicespec" -> "", "user_declared_state" -> "Telangana", "user_declared_district" -> "Hyderbad").filter(f => (f._2.nonEmpty))
    when(deviceRegisterServiceMock.getDeviceProfileMap(register, location))
      .thenReturn(dataMap)

    val deviceDataMap = deviceRegisterServiceMock.getDeviceProfileMap(register, location)
    deviceDataMap("user_declared_state") should be("Telangana")
    deviceDataMap("user_declared_district") should be("Hyderbad")
    deviceDataMap.get("devicespec").isEmpty should be(true)
  }

  "When district is not defined" should "Not resolve" in {
    val deviceSpec = "{\"cpu\":\"abi:  armeabi-v7a  ARMv7 Processor rev 4 (v7l)\",\"make\":\"Micromax Micromax A065\",\"os\":\"Android 4.4.2\"}"
    val uaspec = s"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_13_6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/70.0.3538.77 Safari/537.36"

    val query =
      s"""
         |SELECT
         |  glc.continent_name,
         |  glc.country_iso_code country_code,
         |  glc.country_name,
         |  glc.subdivision_1_iso_code state_code,
         |  glc.subdivision_1_name state,
         |  glc.subdivision_2_name sub_div_2,
         |  glc.city_name city,
         |  glc.subdivision_1_custom_name state_custom,
         |  glc.subdivision_1_custom_code state_code_custom,
         |  glc.subdivision_2_custom_name district_custom
         |FROM $geoLocationCityIpv4TableName gip,
         |  $geoLocationCityTableName glc
         |WHERE glc.country_iso_code = 'IN'
         |  AND gip.geoname_id = glc.geoname_id
         |  AND gip.network_start_integer <= 1935923652
         |  AND gip.network_last_integer >= 1935923652
               """.stripMargin

    when(postgresDBMock.readLocation(query)).thenReturn(List(
      DeviceLocation(continentName = "Asia", countryCode = "IN", countryName = "India", stateCode = "KA",
        state = null, subDivsion2 = null, city = null,
        stateCustom = "", stateCodeCustom = "29", districtCustom = null))
    )

    deviceRegisterActorRef.tell(RegisterDevice(did = "device-001", headerIP = "115.99.217.196", ip_addr = Option("115.99.217.196"), fcmToken = Option("some-token"), producer = Option("prod.diksha.app"), dspec = Option(deviceSpec), uaspec = Option(uaspec), first_access = Option(123456789), user_declared_state = None, user_declared_district = None), ActorRef.noSender)
    verify(postgresDBMock, times(2)).readLocation(query)
  }
}