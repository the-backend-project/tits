package com.github.thxmasj.statemachine;

import reactor.core.publisher.Mono;

public interface OutgoingRequestCreator<T> extends DataRequirer {

  Mono<String> create(
      T data,
      EntityId entityId,
      int eventNumber,
      OutgoingRequestModel<?, T> specification,
      String correlationId,
      Input input
  );

  default Notification notification(
      int eventNumber,
      String message,
      OutgoingRequestModel<?, T> specification
  ) {
    return new Notification.OutgoingRequest(
        eventNumber,
        message,
        specification.subscriber(),
        specification.guaranteed(),
        null
    );
  }

  default Notification notificationResponse(
      int eventNumber,
      int requestEventNumber,
      String message
  ) {
    return new Notification.OutgoingResponse(
        eventNumber,
        message,
        requestEventNumber
    );
  }

  default Notification.OutgoingRequest notification(
      int eventNumber,
      EntityId parentEntity,
      String message,
      OutgoingRequestModel<?, T> specification
  ) {
    return new Notification.OutgoingRequest(
        eventNumber,
        message,
        specification.subscriber(),
        specification.guaranteed(),
        parentEntity
    );
  }

}
