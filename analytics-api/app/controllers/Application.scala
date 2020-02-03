package controllers

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern._
import akka.routing.FromConfig
import javax.inject.Inject
import org.ekstep.analytics.api.service.{CacheRefreshActor, DruidHealthCheckService, _}
import org.ekstep.analytics.api.util._
import org.ekstep.analytics.api.{APIIds, ResponseCode}
import org.ekstep.analytics.framework.util.RestUtil
import play.api.libs.concurrent.Futures
import play.api.libs.json._
import play.api.mvc._
import play.api.{Configuration, Logger}
import javax.inject._

import scala.concurrent.{ExecutionContext, Future}

/**
 * @author mahesh
 */

class Application @Inject() (@Named("client-log-actor") clientLogAPIActor: ActorRef, @Named("druid-health-actor") druidHealthActor: ActorRef, healthCheckService: HealthCheckAPIService, cc: ControllerComponents, system: ActorSystem, configuration: Configuration)(implicit ec: ExecutionContext) extends BaseController(cc, configuration) {

	implicit override val className: String = "controllers.Application"
	val logger: Logger = Logger(this.getClass)

	def getDruidHealthStatus() = Action.async { request: Request[AnyContent] =>
		val result = ask(druidHealthActor, "health").mapTo[String]
		result.map { x =>
			Ok(x).withHeaders(CONTENT_TYPE -> "text/plain");
		}
	}

  def checkAPIhealth() = Action.async { request: Request[AnyContent] =>
    val result = healthCheckService.getHealthStatus();
    Future {
      Ok(result).withHeaders(CONTENT_TYPE -> "application/json");      
    }
  }

	def logClientErrors() = Action.async { request: Request[AnyContent] =>
		val body: String = Json.stringify(request.body.asJson.get)
		try {
			val requestObj = JSONUtils.deserialize[ClientLogRequest](body)
			val validator: ValidatorMessage = validateLogClientErrorRequest(requestObj)
			if (validator.status) {
				clientLogAPIActor.tell(ClientLogRequest(requestObj.request), ActorRef.noSender)
				Future {
					Ok(JSONUtils.serialize(CommonUtil.OK(APIIds.CLIENT_LOG,
						Map("message" -> s"Log captured successfully!"))))
						.withHeaders(CONTENT_TYPE -> "application/json")
				}
			} else {
				Future {
					BadRequest(JSONUtils.serialize(CommonUtil.errorResponse(APIIds.CLIENT_LOG, validator.msg, ResponseCode.CLIENT_ERROR.toString)))
						.withHeaders(CONTENT_TYPE -> "application/json")
				}
			}
		} catch {
			case ex: Exception => Future {
				InternalServerError(JSONUtils.serialize(CommonUtil.errorResponse(APIIds.CLIENT_LOG, ex.getMessage, ResponseCode.SERVER_ERROR.toString )))
					.withHeaders(CONTENT_TYPE -> "application/json")
			}
		}
	}

	def validateLogClientErrorRequest(requestObj: ClientLogRequest): ValidatorMessage = {
			if (!requestObj.validate.status) {
				ValidatorMessage(false, requestObj.validate.msg)
			} else {
				ValidatorMessage(true, "")
			}
	}
}