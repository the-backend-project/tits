package com.github.thxmasj.statemachine.templates.cardpayment;

import com.github.thxmasj.statemachine.BasicEventType;
import com.github.thxmasj.statemachine.BasicEventType.ReadOnly;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Merchant;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Merchant.Location;
import java.util.UUID;

public interface MerchantEvent {

  record MerchantUpdate(
      String merchantName,
      String merchantDisplayName,
      Location merchantLocation,
      String merchantCategoryCode
  ) {}

  EventType<Merchant, Merchant> Create = BasicEventType.of("Create", UUID.fromString("845449d2-d6da-478c-bb98-eac7bcaa4326"), Merchant.class);
  EventType<Void, Void> Suspend = BasicEventType.of("Suspend", UUID.fromString("1b8cf99a-6367-42c7-82d1-879e6d9f9b7f"));
  EventType<Void, Void> Resume = BasicEventType.of("Resume", UUID.fromString("f34b03b8-017f-41ff-80ac-df9a7b84f042"));
  EventType<Void, Void> Delete = BasicEventType.of("Delete", UUID.fromString("a11542b6-f591-4a3e-af3e-0113463d7509"));
  EventType<MerchantUpdate, Merchant> Update = BasicEventType.of("Update", UUID.fromString("2119043f-d547-4b68-ab76-6644662e26ea"), MerchantUpdate.class, Merchant.class);
  EventType<Void, Merchant> Get = new ReadOnly<>("Get", UUID.fromString("419b02d4-ad02-4cdf-a0a9-e20ffd3ad45f"), Void.class, Merchant.class);

}
