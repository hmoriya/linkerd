package io.buoyant.linkerd.protocol.http.istio

import com.twitter.finagle.http.Request
import io.buoyant.k8s.istio.{CurrentIstioPath, IstioRequest}

object HttpIstioRequest {
  def apply(req: Request): IstioRequest[Request] =
    //TODO: match on request scheme
    IstioRequest(req.path, "", req.method.toString, req.host.getOrElse(""), req.headerMap.get, req, CurrentIstioPath())
}

