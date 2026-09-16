package com.github.thxmasj.statemachine.http.outbox;

import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;

import com.github.thxmasj.statemachine.BasicEventType;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.IndexEntityModel;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModelBuilder;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public sealed interface HttpOutboxRequest<I> extends com.github.thxmasj.statemachine.EntityModel permits AtLeastOnce, AtMostOnce {

  Map<State, List<TransitionModelBuilder.TransitionModel<?, ?>>> transitions();

  EventType<I, HttpRequestMessage> requestDispatched();

  static AtLeastOnceBuilder.NameStep atLeastOnce() {
    return AtLeastOnceBuilder.create();
  }

  static AtMostOnceBuilder.NameStep atMostOnce() {
    return AtMostOnceBuilder.create();
  }

  EventType<UUID, UUID> Indexed = BasicEventType.of(
      "Indexed",
      UUID.fromString("da7f9a47-67a9-4a0e-affb-865f665a9564"),
      UUID.class
  );

  Map<State, List<TransitionModel<?, ?>>> indexingTransitions = Map.of(
      Begin, List.of(
          onEvent(Indexed).toSelf().assembleInput().output(d -> d)
      )
  );

  IndexEntityModel<UUID> ProcessReference = IndexEntityModel.ofUUID(
      "ProcessReference",
      UUID.fromString("c0881c13-173e-4672-a6d9-2cdfa57c8cbe")
  );

  IndexEntityModel<UUID> EntityType = IndexEntityModel.ofUUID(
      "EntityType",
      UUID.fromString("0d7a9644-7810-4e91-a8ae-8f39e0f98c67")
  );

  static Map<State, List<TransitionModel<?, ?>>> combine(
      Map<State, List<TransitionModel<?, ?>>> m1,
      Map<State, List<TransitionModel<?, ?>>> m2
  ) {
    return Stream.concat(m1.entrySet().stream(), m2.entrySet().stream())
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            Map.Entry::getValue,
            (_, _) -> {
              throw new IllegalArgumentException("TransitionModel maps can't be combined - they use the same State key");
            }
        ));
  }

}
