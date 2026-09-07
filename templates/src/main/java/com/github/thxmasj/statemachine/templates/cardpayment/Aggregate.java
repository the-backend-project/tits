package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Begin;

import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import java.util.List;
import java.util.UUID;

public enum Aggregate implements EntityModel {

  Payment {
    private final UUID id = UUID.fromString("c56dc62e-24e8-43a7-868f-b87720327ff5");

    @Override
    public UUID id() {
      return id;
    }

    @Override
    public State initialState() {
      return Begin;
    }

//    @Override
//    public EntityModel parentEntity() {
//      return Settlement;
//    }

  },
  Settlement {
    private final UUID id = UUID.fromString("fc1d7b4c-92c7-40b3-9f7e-68a84d8d56f1");

    @Override
    public UUID id() {
      return id;
    }

    @Override
    public List<SecondaryIdModel<?>> secondaryIds() {
      return List.of(Identifiers.BatchNumber, Identifiers.AcquirerBatchNumber);
    }

    @Override
    public State initialState() {
      return Begin;
    }

//    @Override
//    public List<OutboxQueue> queues() {
//      return List.of(Queues.values());
//    }

  },
  Merchant {

    @Override
    public UUID id() {
      return UUID.fromString("3ece621b-2c6a-4e5a-895e-6f7b566ec26f");
    }

    @Override
    public State initialState() {
      return MerchantState.Begin;
    }

    @Override
    public List<SecondaryIdModel<?>> secondaryIds() {
      return List.of(Identifiers.MerchantId);
    }

  }

}
