package com.github.thxmasj.statemachine.templates.cardpayment;

public class AuthenticationDataCreator {

  public record AuthenticationData(
      String paymentRequest,
      String simulation
  ) {}

}
