/*
 * Copyright 2018 Qunar, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package qunar.tc.qmq.store;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * @author keli.wang
 * @since 2017/7/6
 */
@Data
public class GetMessageResult {
    private final List<SegmentBuffer> segmentBuffers = new ArrayList<>(100);
    private int bufferTotalSize = 0;

    private GetMessageStatus status;
    private long minOffset;
    private long maxOffset;
    private long nextBeginOffset;

    private OffsetRange consumerLogRange;

    public GetMessageResult() {
    }

    public GetMessageResult(GetMessageStatus status) {
        this.status = status;
    }

    public void addSegmentBuffer(final SegmentBuffer segmentBuffer) {
        segmentBuffers.add(segmentBuffer);
        bufferTotalSize += segmentBuffer.getSize();
    }

    public int getMessageNum() {
        return segmentBuffers.size();
    }

    public void release() {
        for (SegmentBuffer buffer : segmentBuffers) {
            buffer.release();
        }
    }

}
