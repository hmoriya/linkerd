package io.buoyant.router.h2

import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.context.Contexts
import com.twitter.finagle.stack.{Endpoint, nilStack}
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.finagle._
import com.twitter.finagle.buoyant.h2.{Method, Request, Response, Status, Stream}
import com.twitter.util.{Future, Local}
import io.buoyant.router.context.DstPathCtx
import io.buoyant.test.FunSuite

class NotDog extends Exception

class DangCat extends Exception("meow", new NotDog)

class PerDstPathStreamStatsFilterTest extends FunSuite {

  def setContext(f: Request => Path) =
    Filter.mk[Request, Response, Request, Response] { (req, service) =>
      val save = Local.save()
      try Contexts.local.let(DstPathCtx, Dst.Path(f(req))) { service(req) }
      finally Local.restore(save)
    }

  val service = Service.mk[Request, Response] {
    case r: Request if r.path == "cat" => Future.exception(new DangCat)
    case _ => Future.value(Response(Status.Ok, Stream.empty()))
  }

  val stack = {
    val sf = ServiceFactory(() => Future.value(service))
    val stk = new StackBuilder[ServiceFactory[Request, Response]](nilStack)
    stk.push(PerDstPathStreamStatsFilter.module)
    stk.result ++ Stack.Leaf(Endpoint, sf)
  }

  val dogReq = Request("http", Method.Get, "foo", "dog", Stream.empty())

  val catReq = Request("http", Method.Get, "foo", "cat", Stream.empty())
  test("module installs a per-path StreamStatsFilter") {
    val stats = new InMemoryStatsReceiver
    val params = Stack.Params.empty + param.Stats(stats.scope("pfx"))
    val ctxFilter = setContext({ r => Path.Utf8("req", r.path) })
    val factory = ctxFilter.andThen(stack.make(params))
    val service = await(factory())

    await(service(dogReq))
    assert(await(service(catReq).liftToTry).isThrow)
    await(service(dogReq))

    val pfx = Seq("pfx", "service")
    val catPfx = pfx :+ "req/cat/stream"
    val dogPfx = pfx :+ "req/dog/stream"
    assert(stats.counters == Map(
      (catPfx :+ "requests") -> 1,
      (catPfx :+ "failures") -> 1,
      (catPfx :+ "failures" :+ "io.buoyant.router.DangCat") -> 1,
      (catPfx :+ "failures" :+ "io.buoyant.router.DangCat" :+ "io.buoyant.router.NotDog") -> 1,
      (dogPfx :+ "requests") -> 2,
      (dogPfx :+ "success") -> 2
    ))
    assert(stats.gauges.keys == Set(
      (catPfx :+ "pending"),
      (dogPfx :+ "pending")
    ))
    assert(stats.histogramDetails.keys == Set(
      "pfx/service/req/cat/stream/request_latency_ms",
      "pfx/service/req/dog/stream/request_latency_ms"
    ))
  }

  test("module does nothing when DstPath context not set") {
    val stats = new InMemoryStatsReceiver
    val params = Stack.Params.empty + param.Stats(stats.scope("pfx"))
    val factory = stack.make(params)
    val service = await(factory())

    Contexts.local.letClear(DstPathCtx) {
      await(service(dogReq))
      assert(await(service(catReq).liftToTry).isThrow)
      await(service(dogReq))
    }

    assert(stats.counters.isEmpty)
    assert(stats.gauges.isEmpty)
    assert(stats.histogramDetails.isEmpty)
  }

  test("module does nothing when DstPath context isEmpty") {
    val stats = new InMemoryStatsReceiver
    val params = Stack.Params.empty + param.Stats(stats.scope("pfx"))
    val ctxFilter = setContext(_ => Path.empty)
    val factory = ctxFilter.andThen(stack.make(params))
    val service = await(factory())

    Contexts.local.letClear(DstPathCtx) {
      await(service(dogReq))
      assert(await(service(catReq).liftToTry).isThrow)
      await(service(dogReq))
    }

    assert(stats.counters.isEmpty)
    assert(stats.gauges.isEmpty)
    assert(stats.histogramDetails.isEmpty)
  }

}
