package com.github.thxmasj.backend.templates.cardpayment;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.BasicEventType.ReadOnly;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.templates.cardpayment.PaymentEvent.Merchant;
import com.github.thxmasj.backend.templates.cardpayment.PaymentEvent.Merchant.Location;
import java.util.UUID;

public interface MerchantEvent {

  record MerchantUpdate(
      String merchantDisplayName,
      Location merchantLocation,
      String merchantCategoryCode
  ) {}

  EventType<Merchant, Merchant> MerchantCreated = BasicEventType.of("Merchant created", UUID.fromString("845449d2-d6da-478c-bb98-eac7bcaa4326"), DataType.json(Merchant.class));
  /// The merchant id was claimed, so the merchant is active
  EventType<Merchant, Merchant> MerchantActivated = BasicEventType.of("Merchant activated", UUID.fromString("5d3f1c8e-7a42-4b96-9e0d-2c6b8f4a1e73"), DataType.json(Merchant.class));
  /// Another merchant has the merchant id already, so the merchant is rejected
  EventType<String, String> DuplicateMerchantIdDetected = BasicEventType.of("Duplicate merchant id detected", UUID.fromString("b8e2a6d4-1f39-4c7e-a5b0-6d9e3f2c8a14"), DataType.string());
  EventType<Void, Void> MerchantSuspended = BasicEventType.of("Merchant suspended", UUID.fromString("1b8cf99a-6367-42c7-82d1-879e6d9f9b7f"));
  EventType<Void, Void> MerchantResumed = BasicEventType.of("Merchant resumed", UUID.fromString("f34b03b8-017f-41ff-80ac-df9a7b84f042"));
  EventType<Void, Void> MerchantDeleted = BasicEventType.of("Merchant deleted", UUID.fromString("a11542b6-f591-4a3e-af3e-0113463d7509"));
  EventType<MerchantUpdate, Merchant> MerchantUpdated = BasicEventType.of("Merchant updated", UUID.fromString("2119043f-d547-4b68-ab76-6644662e26ea"), DataType.json(MerchantUpdate.class), DataType.json(Merchant.class));
  EventType<Void, Merchant> MerchantLookedUp = new ReadOnly<>("Merchant looked up", UUID.fromString("419b02d4-ad02-4cdf-a0a9-e20ffd3ad45f"), DataType.none(), DataType.json(Merchant.class));

}
