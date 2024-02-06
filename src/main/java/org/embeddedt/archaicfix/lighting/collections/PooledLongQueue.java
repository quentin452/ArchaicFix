package org.embeddedt.archaicfix.lighting.collections;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

//Implement own queue with pooled segments to reduce allocation costs and reduce idle memory footprint
public class PooledLongQueue {
    private static final int CACHED_QUEUE_SEGMENTS_COUNT = 1 << 12; // 4096
    private static final int QUEUE_SEGMENT_SIZE = 1 << 10; // 1024

    private final Pool pool;

    private Segment cur;
    private Segment last;

    private final AtomicInteger size = new AtomicInteger(0);
    private final AtomicBoolean empty = new AtomicBoolean(true);

    public PooledLongQueue(Pool pool) {
        this.pool = pool;
    }

    public int size() {
        return size.get();
    }

    public void add(final long val) {
        if (cur == null) {
            empty.set(false);
            cur = last = pool.acquire();
        }

        if (last.index == QUEUE_SEGMENT_SIZE) {
            Segment ret = last.next = last.pool.acquire();
            ret.longArray[ret.index++] = val;
            last = ret;
        } else {
            last.longArray[last.index++] = val;
        }

        size.incrementAndGet();
    }

    public void remove(final long val) {
        Segment currentSegment = cur;

        while (currentSegment != null) {
            for (int i = 0; i < currentSegment.index; i++) {
                if (currentSegment.longArray[i] == val) {
                    System.arraycopy(currentSegment.longArray, i + 1, currentSegment.longArray, i, currentSegment.index - i - 1);
                    currentSegment.index--;
                    size.decrementAndGet();

                    if (currentSegment.index == 0) {
                        if (cur == currentSegment) {
                            cur = currentSegment.next;
                        }

                        if (last == currentSegment) {
                            last = null;
                        }

                        currentSegment.release();
                    }

                    return;
                }
            }

            currentSegment = currentSegment.next;
        }
    }

    public LongQueueIterator iterator() {
        return new LongQueueIterator(cur);
    }

    private void clear() {
        Segment segment = cur;

        while (segment != null) {
            Segment next = segment.next;
            segment.release();
            segment = next;
        }

        size.set(0);
        cur = null;
        last = null;
        empty.set(true);
    }

    public class LongQueueIterator {
        private Segment cur;
        private long[] curArray;
        private int index, capacity;

        private LongQueueIterator(Segment cur) {
            this.cur = cur;

            if (this.cur != null) {
                this.curArray = cur.longArray;
                this.capacity = cur.index;
            }
        }

        public boolean hasNext() {
            return cur != null;
        }

        public long next() {
            final long ret = curArray[index++];

            if (index == capacity) {
                index = 0;
                cur = cur.next;

                if (cur != null) {
                    curArray = cur.longArray;
                    capacity = cur.index;
                }
            }

            return ret;
        }

        public void finish() {
            PooledLongQueue.this.clear();
        }
    }

    public static class Pool {
        private final ConcurrentLinkedQueue<Segment> segmentPool = new ConcurrentLinkedQueue<>();

        private Segment acquire() {
            Segment segment = segmentPool.poll();
            if (segment == null) {
                return new Segment(this);
            }
            return segment;
        }

        private void release(Segment segment) {
            if (segmentPool.size() < CACHED_QUEUE_SEGMENTS_COUNT) {
                segmentPool.offer(segment);
            }
        }
    }

    private static class Segment {
        private final long[] longArray = new long[QUEUE_SEGMENT_SIZE];
        private int index = 0;
        private Segment next;
        private final Pool pool;

        private Segment(Pool pool) {
            this.pool = pool;
        }

        private void release() {
            index = 0;
            next = null;
            pool.release(this);
        }
    }
}