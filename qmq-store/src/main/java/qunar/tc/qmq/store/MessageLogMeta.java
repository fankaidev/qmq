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

/**
 * @author keli.wang
 * @since 2017/8/23
 */
@Data
public class MessageLogMeta {
    private final String subject;
    private final long sequence;
    private final long wroteOffset;
    private final int wroteBytes;
    private final short headerSize;
    private final long baseOffset;

    public MessageLogMeta(String subject, long sequence, long wroteOffset, int wroteBytes, short headerSize, long baseOffset) {
        this.subject = subject;
        this.sequence = sequence;
        this.wroteOffset = wroteOffset;
        this.wroteBytes = wroteBytes;
        this.headerSize = headerSize;
        this.baseOffset = baseOffset;
    }


}
