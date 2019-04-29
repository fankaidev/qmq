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

package qunar.tc.qmq.base;

import lombok.Data;
import qunar.tc.qmq.store.SegmentBuffer;

import java.util.ArrayList;
import java.util.List;

/**
 * @author yunfeng.yang
 * @since 2017/10/30
 */
@Data
public class PullMessageResult {
    private final long pullLogOffset;
    private final List<SegmentBuffer> buffers;
    private int bufferTotalSize;
    private int messageNum;

    public static final PullMessageResult EMPTY = new PullMessageResult(-1, new ArrayList<>(), 0, 0);

    public static final PullMessageResult FILTER_EMPTY = new PullMessageResult(-1, new ArrayList<>(), 0, 0);

    public PullMessageResult(long pullLogOffset, List<SegmentBuffer> buffers, int bufferTotalSize, int messageNum) {
        this.pullLogOffset = pullLogOffset;
        this.buffers = buffers;
        this.bufferTotalSize = bufferTotalSize;
        this.messageNum = messageNum;
    }

}
