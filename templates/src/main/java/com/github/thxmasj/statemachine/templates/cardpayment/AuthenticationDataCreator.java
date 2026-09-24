package com.github.thxmasj.statemachine.templates.cardpayment;

public class AuthenticationDataCreator {

  public record AuthenticationData(
      byte[] paymentRequest,
      String simulation
  ) {}

}
