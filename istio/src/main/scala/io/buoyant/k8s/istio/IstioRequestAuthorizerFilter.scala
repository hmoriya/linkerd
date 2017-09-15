package io.buoyant.k8s.istio

import com.twitter.finagle.{Filter, Service}
import com.twitter.logging.Logger
import com.twitter.util._
import io.buoyant.k8s.istio.mixer.MixerClient

trait IstioRequestAuthorizerFilter[Req, Resp] extends Filter[Req, Resp, Req, Resp] {
  val log = Logger(this.getClass.getName)

  def mixerClient: MixerClient

  def toIstioRequest(req: Req): IstioRequest[Req]

  def toIstioResponse(resp: Try[Resp], duration: Duration): IstioResponse[Resp]

  def toFailedResponse(code: Int, reason: String): Resp

  def apply(req: Req, svc: Service[Req, Resp]) = {
    val istioRequest = toIstioRequest(req)

    log.trace("checking Istio pre-conditions for request %s", istioRequest)
    mixerClient.checkPreconditions(istioRequest).flatMap { status =>
      if (status.success) {
        log.trace("Succesful pre-condition check for request: %s", istioRequest)
        callService(req, svc, istioRequest)
      } else {
        log.warning("request [%s] failed Istio pre-condition check: %s-%s", istioRequest, status, status.reason)
        Future.value(toFailedResponse(status.httpCode, status.reason))
      }
    }
  }

  private def callService(req: Req, svc: Service[Req, Resp], istioRequest: IstioRequest[Req]) = {
    val elapsed = Stopwatch.start()

    svc(req).respond { resp =>

      val duration = elapsed()
      val istioResponse = toIstioResponse(resp, duration)

      val _ = report(istioRequest, istioResponse, duration)
    }
  }

  def report(request: IstioRequest[Req], response: IstioResponse[Resp], duration: Duration) = {

    mixerClient.report(
      response.responseCode,
      request.requestedPath,
      request.targetService,
      request.sourceLabel,
      request.targetLabel,
      response.responseDuration
    )
  }
}
