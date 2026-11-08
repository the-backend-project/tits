package com.github.thxmasj.statemachine.http.outbox;

import com.github.thxmasj.statemachine.DataType;
import com.github.thxmasj.statemachine.http.HttpDataType;
import com.github.thxmasj.statemachine.http.outbox.HttpOutboxRequest.BatchedRequest;
import com.github.thxmasj.statemachine.message.http.TypedHttpRequest;
import java.util.UUID;
import java.util.function.Function;

/// The type of the input of an outbox request, i.e. of a request message, or of a [BatchedRequest] for a batched outbox
/// request.
record InputType<I, RQ>(
    DataType<I> dataType,
    Function<I, TypedHttpRequest<RQ>> request,
    Function<I, UUID> batchId
) {

  static <RQ> InputType<TypedHttpRequest<RQ>, RQ> unbatched(DataType<RQ> requestPayloadType) {
    return new InputType<>(HttpDataType.forRequest(requestPayloadType), r -> r, null);
  }

  static <RQ> InputType<BatchedRequest<RQ>, RQ> batched(DataType<RQ> requestPayloadType) {
    return new InputType<>(BatchedRequest.dataType(requestPayloadType), BatchedRequest::request, BatchedRequest::batchId);
  }

  boolean isBatched() {
    return batchId != null;
  }

}
