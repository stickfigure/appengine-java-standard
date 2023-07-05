/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.apphosting.runtime.jetty94;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

import java.nio.ByteBuffer;

/**
 * A handler that can limit the size of message bodies in requests and responses.
 *
 * <p>The optional request and response limits are imposed by checking the {@code Content-Length}
 * header or observing the actual bytes seen by the handler. Handler order is important, in as much
 * as if this handler is before a the {@link org.eclipse.jetty.server.handler.gzip.GzipHandler},
 * then it will limit compressed sized, if it as after the {@link
 * org.eclipse.jetty.server.handler.gzip.GzipHandler} then the limit is applied to uncompressed
 * bytes. If a size limit is exceeded then {@link BadMessageException} is thrown with a {@link
 * org.eclipse.jetty.http.HttpStatus#PAYLOAD_TOO_LARGE_413} status.
 */
public class CoreSizeLimitHandler extends Handler.Wrapper {
  private final long _requestLimit;
  private final long _responseLimit;
  private long _read = 0;
  private long _written = 0;

  /**
   * @param requestLimit The request body size limit in bytes or -1 for no limit
   * @param responseLimit The response body size limit in bytes or -1 for no limit
   */
  public CoreSizeLimitHandler(long requestLimit, long responseLimit) {
    _requestLimit = requestLimit;
    _responseLimit = responseLimit;
  }

  @Override
  public boolean handle(Request request, Response response, Callback callback) throws Exception {
    request.addHttpStreamWrapper(httpStream -> new HttpStream.Wrapper(httpStream) {
      @Override
      public Content.Chunk read() {
        Content.Chunk chunk = super.read();
        if (chunk == null)
          return null;
        if (chunk instanceof Content.Chunk.Error)
          return chunk;

        // Check request content limit.
        ByteBuffer content = chunk.getByteBuffer();
        if (content != null && content.remaining() > 0) {
          _read += content.remaining();
          if (_requestLimit >= 0 && _read > _requestLimit) {
            request.fail(new BadMessageException(413, "Request body is too large: " + _read + ">" + _requestLimit));
            return null;
          }
        }

        return chunk;
      }

      @Override
      public void send(MetaData.Request request, MetaData.Response response, boolean last, ByteBuffer content, Callback callback) {
        // Check response content limit.
        if (content != null && content.remaining() > 0) {
          _written += content.remaining();
          if (_responseLimit >= 0 && _written > _responseLimit) {
            callback.failed(new BadMessageException(500, "Response body is too large: " + _written + ">" + _responseLimit));
            return;
          }
        }

        super.send(request, response, last, content, callback);
      }
    });

    return super.handle(request, response, callback);
  }
}
