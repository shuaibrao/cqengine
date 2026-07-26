/**
 * Copyright 2012-2015 Niall Gallagher
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.googlecode.cqengine.resultset.iterator;

import com.googlecode.cqengine.testutil.ExpectedException;

import com.googlecode.cqengine.testutil.TestAssertions;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.googlecode.cqengine.testutil.TestAssertions.assertEquals;

/**
 * @author niall.gallagher
 */
public class MarkableIteratorTest {

    @Test
    public void testMarkAndResetDuringRead() {
        List<Integer> input = Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);

        MarkableIterator<Integer> markableIterator= new MarkableIterator<Integer>(input.iterator());
        TestAssertions.assertEquals(MarkableIterator.State.READ, markableIterator.state);

        // Advance 5...
        TestAssertions.assertEquals(5, advance(markableIterator, 5));
        TestAssertions.assertEquals(MarkableIterator.State.READ, markableIterator.state);

        // Mark this position...
        markableIterator.mark(Integer.MAX_VALUE);
        TestAssertions.assertEquals(MarkableIterator.State.BUFFER, markableIterator.state);

        // Advance 3...
        TestAssertions.assertEquals(3, advance(markableIterator, 3));
        TestAssertions.assertEquals(MarkableIterator.State.BUFFER, markableIterator.state);

        // Reset to position 5...
        markableIterator.reset();
        TestAssertions.assertEquals(MarkableIterator.State.REPLAY, markableIterator.state);
        TestAssertions.assertEquals(Arrays.asList(5, 6, 7, 8, 9), remainder(markableIterator));
        TestAssertions.assertEquals(MarkableIterator.State.BUFFER, markableIterator.state);
    }

    @Test
    public void testMarkAndResetDuringReplay() {
        List<Integer> input = Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);

        MarkableIterator<Integer> markableIterator= new MarkableIterator<Integer>(input.iterator());
        TestAssertions.assertEquals(MarkableIterator.State.READ, markableIterator.state);

        // Advance 5...
        TestAssertions.assertEquals(5, advance(markableIterator, 5));
        TestAssertions.assertEquals(MarkableIterator.State.READ, markableIterator.state);

        // Mark this position...
        markableIterator.mark(Integer.MAX_VALUE);
        TestAssertions.assertEquals(MarkableIterator.State.BUFFER, markableIterator.state);

        // Advance 3...
        TestAssertions.assertEquals(3, advance(markableIterator, 3));
        TestAssertions.assertEquals(MarkableIterator.State.BUFFER, markableIterator.state);

        // Reset to position 5...
        markableIterator.reset();
        TestAssertions.assertEquals(MarkableIterator.State.REPLAY, markableIterator.state);

        // Advance/replay 1 (should find integer 5)...
        TestAssertions.assertEquals(Collections.singletonList(5), take(markableIterator, 1));
        TestAssertions.assertEquals(MarkableIterator.State.REPLAY, markableIterator.state);

        // Mark this position 6...
        markableIterator.mark(Integer.MAX_VALUE);
        TestAssertions.assertEquals(MarkableIterator.State.REPLAY, markableIterator.state);

        // Advance 2 (should find integers 6 & 7)...
        TestAssertions.assertEquals(Arrays.asList(6, 7), take(markableIterator, 2));
        TestAssertions.assertEquals(MarkableIterator.State.REPLAY, markableIterator.state);

        // Advance 1 (should find integer 8)...
        TestAssertions.assertEquals(Collections.singletonList(8), take(markableIterator, 1));
        TestAssertions.assertEquals(MarkableIterator.State.BUFFER, markableIterator.state);

        // Reset to position 6...
        markableIterator.reset();
        // Replay the remainder of the buffer...
        TestAssertions.assertEquals(MarkableIterator.State.REPLAY, markableIterator.state);
        TestAssertions.assertEquals(Arrays.asList(6, 7), take(markableIterator, 2));
        TestAssertions.assertEquals(MarkableIterator.State.REPLAY, markableIterator.state);
        // Then read the next object from the backing iterator, and note that state changes...
        TestAssertions.assertEquals(Collections.singletonList(8), take(markableIterator, 1));
        TestAssertions.assertEquals(MarkableIterator.State.REPLAY, markableIterator.state);
        // Read the rest of the stream from backing iterator...
        TestAssertions.assertEquals(Collections.singletonList(9), remainder(markableIterator));
        TestAssertions.assertEquals(MarkableIterator.State.BUFFER, markableIterator.state);
    }

    @Test
    public void testMarkAndResetDuringBuffer() {
        List<Integer> input = Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);

        MarkableIterator<Integer> markableIterator= new MarkableIterator<Integer>(input.iterator());
        TestAssertions.assertEquals(MarkableIterator.State.READ, markableIterator.state);

        // Advance 5...
        TestAssertions.assertEquals(Arrays.asList(0, 1, 2, 3, 4), take(markableIterator, 5));
        TestAssertions.assertEquals(MarkableIterator.State.READ, markableIterator.state);

        // Mark this position...
        markableIterator.mark(1);
        TestAssertions.assertEquals(MarkableIterator.State.BUFFER, markableIterator.state);

        // Advance 1...
        TestAssertions.assertEquals(Collections.singletonList(5), take(markableIterator, 1));
        TestAssertions.assertEquals(MarkableIterator.State.BUFFER, markableIterator.state);

        // Mark this position...
        markableIterator.mark(2);
        TestAssertions.assertEquals(MarkableIterator.State.BUFFER, markableIterator.state);

        // Advance 1...
        TestAssertions.assertEquals(Collections.singletonList(6), take(markableIterator, 1));
        TestAssertions.assertEquals(MarkableIterator.State.BUFFER, markableIterator.state);

        // Reset to position 6...
        markableIterator.reset();
        TestAssertions.assertEquals(MarkableIterator.State.REPLAY, markableIterator.state);


        // Replay the remainder of the buffer...
        TestAssertions.assertEquals(Collections.singletonList(6), take(markableIterator, 1));
        TestAssertions.assertEquals(MarkableIterator.State.REPLAY, markableIterator.state);
        TestAssertions.assertEquals(Collections.singletonList(7), take(markableIterator, 1));
        TestAssertions.assertEquals(MarkableIterator.State.BUFFER, markableIterator.state);
        TestAssertions.assertEquals(Collections.singletonList(8), take(markableIterator, 1));
        // Mark is invalidated...
        TestAssertions.assertEquals(MarkableIterator.State.READ, markableIterator.state);
        TestAssertions.assertEquals(Collections.singletonList(9), remainder(markableIterator));
        TestAssertions.assertEquals(MarkableIterator.State.READ, markableIterator.state);
    }

    @Test
    @ExpectedException(IllegalStateException.class)
    public void testResetWithoutMark() {
        List<Integer> input = Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);

        MarkableIterator<Integer> markableIterator= new MarkableIterator<Integer>(input.iterator());
        TestAssertions.assertEquals(MarkableIterator.State.READ, markableIterator.state);

        markableIterator.reset();
    }

    @Test
    @ExpectedException(IllegalStateException.class)
    public void testMarkInvalidated() {
        List<Integer> input = Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);

        MarkableIterator<Integer> markableIterator= new MarkableIterator<Integer>(input.iterator());
        TestAssertions.assertEquals(MarkableIterator.State.READ, markableIterator.state);

        markableIterator.mark(1);
        TestAssertions.assertEquals(MarkableIterator.State.BUFFER, markableIterator.state);
        TestAssertions.assertEquals(Arrays.asList(0, 1), take(markableIterator, 2));
        TestAssertions.assertEquals(MarkableIterator.State.READ, markableIterator.state);
        markableIterator.reset();
    }

    static int advance(Iterator<?> iterator, int numberToAdvance) {
        int advanced = 0;
        while (advanced < numberToAdvance && iterator.hasNext()) {
            iterator.next();
            advanced++;
        }
        return advanced;
    }

    static <T> List<T> take(Iterator<T> iterator, int maximumSize) {
        List<T> values = new ArrayList<T>(maximumSize);
        while (values.size() < maximumSize && iterator.hasNext()) {
            values.add(iterator.next());
        }
        return values;
    }

    static <T> List<T> remainder(Iterator<T> iterator) {
        List<T> values = new ArrayList<T>();
        iterator.forEachRemaining(values::add);
        return values;
    }
}
