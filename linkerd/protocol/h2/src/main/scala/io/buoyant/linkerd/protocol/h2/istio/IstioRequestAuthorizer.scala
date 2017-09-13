package io.buoyant.linkerd.protocol.h2.istio

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle._
import com.twitter.finagle.buoyant.h2.{Request, Response}
import com.twitter.util.Stopwatch
import io.buoyant.config.types.Port
import io.buoyant.k8s.istio.mixer.MixerClient
import io.buoyant.k8s.istio.{IstioConfigurator, IstioRequestAuthorizerBase, _}
import io.buoyant.linkerd.RequestAuthorizerInitializer
import io.buoyant.linkerd.protocol.h2.H2RequestAuthorizerConfig

class IstioRequestAuthorizer(val mixerClient: MixerClient, params: Stack.Params) extends Filter[Request, Response, Request, Response] with IstioRequestAuthorizerBase {

  def apply(req: Request, svc: Service[Request, Response]) = {
    val istioRequest = H2IstioRequest(req)

    val elapsed = Stopwatch.start()

    svc(req).respond { ret =>

      val duration = elapsed()
      val istioResponse = H2IstioResponse(ret, duration)

      val _ = report(istioRequest, istioResponse, duration)
    }
  }
}

case class IstioRequestAuthorizerConfig(
  mixerHost: Option[String] = Some(DefaultMixerHost),
  mixerPort: Option[Port] = Some(Port(DefaultMixerPort))
) extends H2RequestAuthorizerConfig with IstioConfigurator {

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
  val configClass = classOf[IstioRequestAuthorizerConfig]
  override val configId = "io.l5d.k8s.istio"
}

object IstioRequestAuthorizerInitializer extends IstioRequestAuthorizerInitializer
