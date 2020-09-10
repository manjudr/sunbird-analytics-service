package controllers

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.routing.FromConfig
import javax.inject.{Inject, Named}
import org.ekstep.analytics.api.service._
import org.ekstep.analytics.api.util.{APILogger, CacheUtil, CommonUtil, JSONUtils}
import org.ekstep.analytics.api.{APIIds, ResponseCode, _}
import play.api.Configuration
import play.api.libs.json.Json
import play.api.mvc.{Request, Result, _}

import scala.concurrent.{ExecutionContext, Future}

/**
  * @author Amit Behera, mahesh
  */

class JobController @Inject() (
                                @Named("job-service-actor") jobAPIActor: ActorRef,
                                system: ActorSystem,
                                configuration: Configuration,
                                cc: ControllerComponents,
                                cacheUtil: CacheUtil
                              )(implicit ec: ExecutionContext) extends BaseController(cc, configuration) {

  def dataRequest() = Action.async { request: Request[AnyContent] =>
    val body: String = Json.stringify(request.body.asJson.get)
    val channelId = request.headers.get("X-Channel-ID").getOrElse("")
    val consumerId = request.headers.get("X-Consumer-ID").getOrElse("")
    val checkFlag = if (config.getBoolean("dataexhaust.authorization_check")) authorizeDataExhaustRequest(consumerId, channelId) else (true, None)
    if (checkFlag._1) {
       val res = ask(jobAPIActor, DataRequest(body, channelId, config)).mapTo[Response]
       res.map { x =>
         result(x.responseCode, JSONUtils.serialize(x))
       }
    } else {
       APILogger.log(checkFlag._2.get)
       errResponse(checkFlag._2.get, APIIds.DATA_REQUEST, ResponseCode.FORBIDDEN.toString)
    }

  }

  def getJob(tag: String, requestId: String) = Action.async { request: Request[AnyContent] =>

    val channelId = request.headers.get("X-Channel-ID").getOrElse("")
    val consumerId = request.headers.get("X-Consumer-ID").getOrElse("")
    val checkFlag = if (config.getBoolean("dataexhaust.authorization_check")) authorizeDataExhaustRequest(consumerId, channelId) else (true, None)
    if (checkFlag._1) {
       val appendedTag = tag + ":" + channelId
       val res = ask(jobAPIActor, GetDataRequest(appendedTag, requestId, config)).mapTo[Response]
       res.map { x =>
          result(x.responseCode, JSONUtils.serialize(x))
        }
    } else {
        APILogger.log(checkFlag._2.get)
        errResponse(checkFlag._2.get, APIIds.GET_DATA_REQUEST, ResponseCode.FORBIDDEN.toString)
    }
  }

  def getJobList(tag: String) = Action.async { request: Request[AnyContent] =>

    val channelId = request.headers.get("X-Channel-ID").getOrElse("")
    val consumerId = request.headers.get("X-Consumer-ID").getOrElse("")
    val checkFlag = if (config.getBoolean("dataexhaust.authorization_check")) authorizeDataExhaustRequest(consumerId, channelId) else (true, None)
    if (checkFlag._1) {
       val appendedTag = tag + ":" + channelId
       val limit = Integer.parseInt(request.getQueryString("limit").getOrElse(config.getString("data_exhaust.list.limit")))
       val res = ask(jobAPIActor, DataRequestList(appendedTag, limit, config)).mapTo[Response]
       res.map { x =>
          result(x.responseCode, JSONUtils.serialize(x))
       }
    }
    else {
       APILogger.log(checkFlag._2.get)
       errResponse(checkFlag._2.get, APIIds.GET_DATA_REQUEST_LIST, ResponseCode.FORBIDDEN.toString)
    }
  }

  def getTelemetry(datasetId: String) = Action.async { request: Request[AnyContent] =>

    val since = request.getQueryString("since").getOrElse("")
    val from = request.getQueryString("from").getOrElse("")
    val to = request.getQueryString("to").getOrElse("")

    val channelId = request.headers.get("X-Channel-ID").getOrElse("")
    val consumerId = request.headers.get("X-Consumer-ID").getOrElse("")
    val checkFlag = if (config.getBoolean("dataexhaust.authorization_check")) authorizeDataExhaustRequest(consumerId, channelId) else (true, None)
    if (checkFlag._1) {
      APILogger.log(s"Authorization Successfull for X-Consumer-ID='$consumerId' and X-Channel-ID='$channelId'")
      val res = ask(jobAPIActor, ChannelData(channelId, datasetId, from, to, since, config)).mapTo[Response]
      res.map { x =>
        result(x.responseCode, JSONUtils.serialize(x))
      }
    } else {
        APILogger.log(checkFlag._2.get)
        errResponse(checkFlag._2.get, APIIds.CHANNEL_TELEMETRY_EXHAUST, ResponseCode.FORBIDDEN.toString)
    }
  }

  private def errResponse(msg: String, apiId: String, responseCode: String): Future[Result] = {
     val res = CommonUtil.errorResponse(apiId, msg, responseCode)
     Future {
        result(res.responseCode, JSONUtils.serialize(res))
     }
  }

  def refreshCache(cacheType: String) = Action { implicit request =>
      cacheType match {
          case "ConsumerChannel" =>
            cacheUtil.initConsumerChannelCache()
          case "DeviceLocation" =>
            cacheUtil.initDeviceLocationCache()
      }
      result("OK", JSONUtils.serialize(CommonUtil.OK(APIIds.CHANNEL_TELEMETRY_EXHAUST, Map("msg" -> s"$cacheType cache refreshed successfully"))))
  }

  def authorizeDataExhaustRequest(consumerId: String, channelId: String): (Boolean, Option[String]) = {
    if (channelId.nonEmpty) {
        APILogger.log(s"Authorizing $consumerId and $channelId")
        val whitelistedConsumers = config.getStringList("channel.data_exhaust.whitelisted.consumers")
        // if consumerId is present in whitelisted consumers, skip auth check
        if (consumerId.nonEmpty && whitelistedConsumers.contains(consumerId)) (true, None)
        else {
            val status = Option(cacheUtil.getConsumerChannelTable().get(consumerId, channelId))
            if (status.getOrElse(0) == 1) (true, None) else (false, Option(s"Given X-Consumer-ID='$consumerId' and X-Channel-ID='$channelId' are not authorized"))
        }
    }
    else (false, Option("X-Channel-ID is missing in request header"))
  }
}