package com.github.thxmasj.statemachine.templates.cardpayment;

import com.github.thxmasj.statemachine.OutgoingRequestCreator;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Tuples.Tuple3;
import com.github.thxmasj.statemachine.templates.cardpayment.AcquirerResponse.ReconciliationValues;
import com.github.thxmasj.statemachine.templates.cardpayment.ApprovedCaptureDataCreator.ApprovedCaptureData;
import com.github.thxmasj.statemachine.templates.cardpayment.ApprovedRefundDataCreator.ApprovedRefundData;
import com.github.thxmasj.statemachine.templates.cardpayment.AuthenticationDataCreator.AuthenticationData;
import com.github.thxmasj.statemachine.templates.cardpayment.AuthorisationReversalDataCreator.AuthorisationReversalData;
import com.github.thxmasj.statemachine.templates.cardpayment.CaptureRequestDataCreator.CaptureRequestData;
import com.github.thxmasj.statemachine.templates.cardpayment.DeclinedRefundDataCreator.DeclinedRefundData;
import com.github.thxmasj.statemachine.templates.cardpayment.FailedRefundDataCreator.FailedRefundData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationResult;
import com.github.thxmasj.statemachine.templates.cardpayment.PreauthorisationReversalDataCreator.PreauthorisationReversalData;
import com.github.thxmasj.statemachine.templates.cardpayment.RefundRequestDataCreator.RefundRequestData;
import com.github.thxmasj.statemachine.templates.cardpayment.RefundReversalDataCreator.RefundReversalData;
import com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.CutOff;
import java.util.UUID;

public class OutgoingRequests {

  public interface Authentication extends OutgoingRequestCreator<AuthenticationData> {
    default UUID id() {return UUID.fromString("bb64d867-2d78-47bf-af75-d8b6ae646aaa");}
    default String name() {return "Authentication";}
  }
  public interface Preauthorisation extends OutgoingRequestCreator<Tuple2<PaymentEvent.Authorisation, AuthenticationResult>> {
    default UUID id() {return UUID.fromString("c0e84149-a4aa-4e37-900f-a520ea8c9327");}
    default String name() {return "Preauthorisation";}
  }
  public interface PreauthorisationReversal extends OutgoingRequestCreator<PreauthorisationReversalData> {
    default UUID id() {return UUID.fromString("6f311a5d-e8ab-413f-897e-b3fb813e17a2");}
    default String name() {return "PreauthorisationReversal";}
  }
  public interface Authorisation extends OutgoingRequestCreator<Tuple2<PaymentEvent.Authorisation, AuthenticationResult>> {
    default UUID id() {return UUID.fromString("564a47ea-f414-4690-9700-19554dd81bf3");}
    default String name() {return "Authorisation";}
  }
  public interface AuthorisationReversal extends OutgoingRequestCreator<AuthorisationReversalData> {
    default UUID id() {return UUID.fromString("96bff53a-2401-4cbc-a584-83aec6608bd3");}
    default String name() {return "AuthorisationReversal";}
  }
  public interface RolledBackPreauthorisationRequest extends OutgoingRequestCreator<PreauthorisationReversalData> {
    default UUID id() {return UUID.fromString("b9f4230d-1a55-4453-a9e7-7a6079985171");}
    default String name() {return "RolledBackPreauthorisationRequest";}
  }
  public interface RolledBackAuthorisationRequest extends OutgoingRequestCreator<AuthorisationReversalData> {
    default UUID id() {return UUID.fromString("86293a8d-03e8-4e89-9857-8d44bc31afac");}
    default String name() {return "RolledBackAuthorisationRequest";}
  }
  public interface FailedAuthentication extends OutgoingRequestCreator<Tuple2<PaymentEvent.Authorisation, AuthenticationResult>> {
    default UUID id() {return UUID.fromString("146935ba-372e-44bc-b4ac-4f0474c71daf");}
    default String name() {return "FailedAuthentication";}
  }
  public interface FailedTokenValidation extends OutgoingRequestCreator<Tuple2<PaymentEvent.Authorisation, AuthenticationResult>> {
    default UUID id() {return UUID.fromString("f287fc81-0c89-4e35-98fe-1a5c5ecbfb2d");}
    default String name() {return "FailedTokenValidation";}
  }
  public interface FailedAuthorisation extends OutgoingRequestCreator<Tuple2<PaymentEvent.Authorisation, AuthenticationResult>> {
    default UUID id() {return UUID.fromString("b650af23-8d77-4172-a5b9-5663557310fc");}
    default String name() {return "FailedAuthorisation";}
  }
  public interface DeclinedAuthorisation extends OutgoingRequestCreator<Tuple2<PaymentEvent.Authorisation, AcquirerResponse>> {
    default UUID id() {return UUID.fromString("88a79f93-3345-4f44-830a-19cd719d4209");}
    default String name() {return "DeclinedAuthorisation";}
  }
  public interface ApprovedPreauthorisation extends OutgoingRequestCreator<Tuple2<PaymentEvent.Authorisation, AcquirerResponse>> {
    default UUID id() {return UUID.fromString("0d76d725-d55c-41f5-be8f-f8f3633de7c8");}
    default String name() {return "ApprovedPreauthorisation";}
  }
  public interface ApprovedCapture extends OutgoingRequestCreator<ApprovedCaptureData> {
    default UUID id() {return UUID.fromString("3e78b6b7-057b-4d3c-a91e-33a61cebc429");}
    default String name() {return "ApprovedCapture";}
  }
  public interface ApprovedAuthorisation extends OutgoingRequestCreator<Tuple2<PaymentEvent.Authorisation, AcquirerResponse>> {
    default UUID id() {return UUID.fromString("9bd938ed-bf98-4e37-9de5-10606719585a");}
    default String name() {return "ApprovedAuthorisation";}
  }
  public interface Capture extends OutgoingRequestCreator<CaptureRequestData> {
    default UUID id() {return UUID.fromString("4120c908-4160-48a0-88af-c500494aaefd");}
    default String name() {return "Capture";}
  }
  public interface CaptureTooLate extends OutgoingRequestCreator<Tuple3<PaymentEvent.Authorisation, AuthenticationResult, PaymentEvent.Capture>> {
    default UUID id() {return UUID.fromString("d7548893-fd35-4d60-b3db-68a63ee60227");}
    default String name() {return "CaptureTooLate";}
  }
  public interface RefundAuthorisation extends OutgoingRequestCreator<RefundRequestData> {
    default UUID id() {return UUID.fromString("ffae8e0e-42d9-49a8-840e-37df49bd77c6");}
    default String name() {return "RefundAuthorisation";}
  }
  public interface RefundReversal extends OutgoingRequestCreator<RefundReversalData> {
    default UUID id() {return UUID.fromString("0cf16704-4b46-4fb1-8078-81ab223a51b3");}
    default String name() {return "RefundReversal";}
  }
  public interface FailedRefund extends OutgoingRequestCreator<FailedRefundData> {
    default UUID id() {return UUID.fromString("4f3d9b07-7544-489a-b107-b963d7102125");}
    default String name() {return "FailedRefund";}
  }
  public interface ApprovedRefund extends OutgoingRequestCreator<ApprovedRefundData> {
    default UUID id() {return UUID.fromString("33fb79ec-6fc8-43d9-863d-167a49e15566");}
    default String name() {return "ApprovedRefund";}
  }
  public interface DeclinedRefund extends OutgoingRequestCreator<DeclinedRefundData> {
    default UUID id() {return UUID.fromString("70e88428-995a-4d86-8397-f54b3abcfdaa");}
    default String name() {return "DeclinedRefund";}
  }
  public interface ApprovedCutOff extends OutgoingRequestCreator<Tuple2<CutOff, ReconciliationValues>> {
    default UUID id() {return UUID.fromString("f8002ded-f8ae-4d7e-a7de-44a7bba4d24d");}
    default String name() {return "ApprovedCutOff";}
  }
  public interface Reconciliation extends OutgoingRequestCreator<Tuple2<BatchNumber, AcquirerBatchNumber>> {
    default UUID id() {return UUID.fromString("7c676c66-6465-4dc6-b4e5-f9266ad52c1e");}
    default String name() {return "Reconciliation";}
  }
}
