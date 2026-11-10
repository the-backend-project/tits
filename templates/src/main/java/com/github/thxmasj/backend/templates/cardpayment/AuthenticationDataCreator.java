package com.github.thxmasj.backend.templates.cardpayment;

public class AuthenticationDataCreator {

  public record AuthenticationData(
      byte[] paymentRequest,
      String simulation
  ) {}

}
