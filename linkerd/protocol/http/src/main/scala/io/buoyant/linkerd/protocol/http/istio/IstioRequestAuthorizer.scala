package io.buoyant.linkerd.protocol.http.istio

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle._
import com.twitter.finagle.http.{Request, Response}
import com.twitter.util._
import io.buoyant.config.types.Port
import io.buoyant.k8s.istio.mixer.MixerClient
import io.buoyant.k8s.istio.{IstioConfigurator, IstioRequestAuthorizerBase, _}
import io.buoyant.linkerd.RequestAuthorizerInitializer
import io.buoyant.linkerd.protocol.HttpRequestAuthorizerConfig

class IstioRequestAuthorizer(val mixerClient: MixerClient, params: Stack.Params) extends Filter[Request, Response, Request, Response] with IstioRequestAuthorizerBase {

  def apply(req: Request, svc: Service[Request, Response]) = {
    val istioRequest = HttpIstioRequest(req)

    val elapsed = Stopwatch.start()

    svc(req).respond { ret =>

      val duration = elapsed()
      val istioResponse = HttpIstioResponse(ret, duration)

      val _ = report(istioRequest, istioResponse, duration)
    }
  }
}

case class IstioRequestAuthorizerInitializerConfig(
  mixerHost: Option[String] = Some(DefaultMixerHost),
  mixerPort: Option[Port] = Some(Port(DefaultMixerPort))
) extends HttpRequestAuthorizerConfig with IstioConfigurator {

  @JsonIgnore
  override def role = Stack.Role("IstioRequestAuthorizer")
  @JsonIgnore
  override def description = "Checks if request is authorised"

  @JsonIgnore
  override def parameters = Seq()

  @JsonIgnore
  def mk(params: Stack.Params): Filter[Request, Response, Request, Response] = {
    new IstioRequestAuthorizer(mkMixerClient(mixerHost, mixerPort), params)
  }
}

class IstioRequestAuthorizerInitializer extends RequestAuthorizerInitializer {
  val configClass = classOf[IstioRequestAuthorizerInitializerConfig]
  override val configId = "io.l5d.k8s.istio"
}

object IstioRequestAuthorizerInitializer extends IstioRequestAuthorizerInitializer
