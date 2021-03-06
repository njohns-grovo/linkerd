package io.buoyant.linkerd
package protocol

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{Path, Stack}
import com.twitter.finagle.buoyant.linkerd.DelayedRelease
import com.twitter.finagle.client.StackClient
import com.twitter.finagle.buoyant.linkerd.{Headers, HttpTraceInitializer}
import com.twitter.finagle.service.Retries
import io.buoyant.linkerd.protocol.http.{AccessLogger, ResponseClassifiers}
import io.buoyant.router.{Http, RoutingFactory}

class HttpInitializer extends ProtocolInitializer.Simple {
  val name = "http"

  protected type Req = com.twitter.finagle.http.Request
  protected type Rsp = com.twitter.finagle.http.Response

  protected val defaultRouter = {
    val pathStack = Http.router.pathStack
      .prepend(Headers.Dst.PathFilter.module)
      .replace(StackClient.Role.prepFactory, DelayedRelease.module)
      .prepend(http.ErrorResponder.module)
    val boundStack = Http.router.boundStack
      .prepend(Headers.Dst.BoundFilter.module)
    val clientStack = Http.router.clientStack
      .prepend(http.AccessLogger.module)
      .replace(HttpTraceInitializer.role, HttpTraceInitializer.clientModule)
      .insertAfter(Retries.Role, http.StatusCodeStatsFilter.module)
      .insertAfter(StackClient.Role.prepConn, Headers.Ctx.clientModule)

    Http.router
      .withPathStack(pathStack)
      .withBoundStack(boundStack)
      .withClientStack(clientStack)
      .configured(RoutingFactory.DstPrefix(Path.Utf8(name)))
  }

  protected val defaultServer = {
    val stk = Http.server.stack
      .replace(HttpTraceInitializer.role, HttpTraceInitializer.serverModule)
      .prepend(Headers.Ctx.serverModule)
      .prepend(http.ErrorResponder.module)
    Http.server.withStack(stk)
  }

  val configClass = classOf[HttpConfig]

  override def defaultServerPort: Int = 4140
}

object HttpInitializer extends HttpInitializer

case class HttpConfig(
  httpAccessLog: Option[String],
  identifier: Option[HttpIdentifierConfig]
) extends RouterConfig {

  var client: Option[ClientConfig] = None
  var servers: Seq[ServerConfig] = Nil

  @JsonIgnore
  override def baseResponseClassifier =
    ResponseClassifiers.NonRetryableServerFailures orElse super.baseResponseClassifier

  @JsonIgnore
  override val protocol: ProtocolInitializer = HttpInitializer

  @JsonIgnore
  override def routerParams: Stack.Params = super.routerParams
    .maybeWith(httpAccessLog.map(AccessLogger.param.File(_)))
    .maybeWith(identifier.map(id => Http.param.HttpIdentifier(id.newIdentifier)))
}
