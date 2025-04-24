package com.github.thxmasj.statemachine.http;

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.resolver.DefaultAddressResolverGroup;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import reactor.netty.http.Http2SslContextSpec;
import reactor.netty.http.client.HttpClient;

public class NettyHttpClientBuilder {

  private Duration responseTimeout;
  private boolean trustServerCertificate;
  private List<X509Certificate> serverTrustCertificates;
  private String clientCertificate;
  private String clientKey;
  private List<String> tlsProtocols;
  private List<String> tlsCiphers;

  public NettyHttpClientBuilder responseTimeout(Duration responseTimeout) {
    this.responseTimeout = responseTimeout;
    return this;
  }

  public NettyHttpClientBuilder trustServerCertificate(boolean trustServerCertificate) {
    this.trustServerCertificate = trustServerCertificate;
    return this;
  }

  public NettyHttpClientBuilder serverTrustCertificates(List<X509Certificate> serverTrustCertificates) {
    this.serverTrustCertificates = serverTrustCertificates;
    return this;
  }

  public NettyHttpClientBuilder serverTrustCertificate(X509Certificate serverTrustCertificate) {
    if (serverTrustCertificates == null)
      serverTrustCertificates = List.of(serverTrustCertificate);
    else
      serverTrustCertificates.add(serverTrustCertificate);
    return this;
  }

  public NettyHttpClientBuilder clientCertificateAndKey(String clientCertificate, String clientKey) {
    this.clientCertificate = clientCertificate;
    this.clientKey = clientKey;
    return this;
  }

  public NettyHttpClientBuilder tlsProtocols(List<String> tlsProtocols) {
    this.tlsProtocols = tlsProtocols;
    return this;
  }

  public NettyHttpClientBuilder tlsCiphers(List<String> tlsCiphers) {
    this.tlsCiphers = tlsCiphers;
    return this;
  }

  public HttpClient build() {
    var httpClient = HttpClient.create()
        .responseTimeout(responseTimeout)
        .resolver(DefaultAddressResolverGroup.INSTANCE);
    var sslContextSpec = Http2SslContextSpec.forClient();
    sslContextSpec.configure(ssl -> ssl.protocols(tlsProtocols).ciphers(tlsCiphers));
    if (trustServerCertificate) {
      sslContextSpec.configure(ssl -> ssl.trustManager(InsecureTrustManagerFactory.INSTANCE));
    } else if (serverTrustCertificates != null) {
      sslContextSpec.configure(ssl -> ssl.trustManager(serverTrustCertificates));
    }
    if (clientCertificate != null) {
      sslContextSpec.configure(ssl -> ssl.keyManager(
          new ByteArrayInputStream(clientCertificate.getBytes(StandardCharsets.UTF_8)),
          new ByteArrayInputStream(clientKey.getBytes(StandardCharsets.UTF_8))
      ));
    }
    httpClient = httpClient.secure(ssl -> ssl.sslContext(sslContextSpec));
    return httpClient;
  }

}
