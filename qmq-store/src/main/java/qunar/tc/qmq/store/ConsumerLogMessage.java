package qunar.tc.qmq.store;

import lombok.Data;

/**
 * @author fankai
 */
@Data
class ConsumerLogMessage {

    private final long sequence;
    private final long offset;
    private final int size;
    private final short headerSize;

    ConsumerLogMessage(long sequence, long offset, int size, short headerSize) {
        this.sequence = sequence;
        this.offset = offset;
        this.size = size;
        this.headerSize = headerSize;
    }

}
