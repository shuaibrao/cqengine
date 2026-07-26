/**
 * Copyright 2012-2015 Niall Gallagher
 * Modified by Shuaib Rao in 2026.
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
package com.googlecode.cqengine.resultset.order;

import com.googlecode.cqengine.attribute.*;
import com.googlecode.cqengine.query.option.AttributeOrder;
import com.googlecode.cqengine.query.option.QueryOptions;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A comparator which sorts result objects according to a list of attributes each with an associated preference for
 * ascending or descending order.
 *
 * @author Roberto Socrates
 * @author Niall Gallagher
 */
public class AttributeOrdersComparator<O> implements Comparator<O> {

    final List<AttributeOrder<O>> attributeSortOrders;
    final QueryOptions queryOptions;
    private final Object tieBreakerLock = new Object();
    // Weak identity keys: a cached comparator must not pin every hash-colliding object it ever compared.
    private final Map<TieBreakerKey, Long> tieBreakerIds = new HashMap<TieBreakerKey, Long>();
    private final ReferenceQueue<Object> staleTieBreakerKeys = new ReferenceQueue<Object>();
    private long nextTieBreakerId;

    public AttributeOrdersComparator(List<AttributeOrder<O>> attributeSortOrders, QueryOptions queryOptions) {
        this.attributeSortOrders = Collections.unmodifiableList(
                new ArrayList<AttributeOrder<O>>(attributeSortOrders));
        this.queryOptions = queryOptions;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"}) // Consumes AttributeOrder's legacy raw-Comparable API.
    public int compare(O o1, O o2) {
        for (AttributeOrder<O> attributeOrder : attributeSortOrders) {
            Attribute<O, ? extends Comparable> attribute = attributeOrder.getAttribute();
            int comparison;
            if (attribute instanceof OrderControlAttribute) {
                OrderControlAttribute<O> orderControl = (OrderControlAttribute<O>)(OrderControlAttribute)(attribute);
                comparison = orderControl.getValue(o1, queryOptions).compareTo(orderControl.getValue(o2, queryOptions));
                if (comparison != 0) {
                    // One of the objects has values for the delegate attribute encapsulated in OrderControlAttribute,
                    // and the other object does not. Return this difference so that they will be ordered relative to
                    // each other based whether they have values or not...
                    return comparison;
                }
                attribute = (Attribute<O, ? extends Comparable>) orderControl.getDelegateAttribute();
            }
            comparison = compareAttributeValues(attribute, o1, o2);
            if (comparison != 0) {
                // Found a difference. Invert the sign if order is descending, and return it...
                return attributeOrder.isDescending() ? comparison * -1 : comparison;
            }
            // else continue checking remaining attributes.
        }
        // No differences found according to ordering specified, but in case this comparator
        // will be used for object equality testing, return 0 only if objects really are equal...
        if (o1.equals(o2)) {
            return 0;
        }

        // At this point we have run out of attributeSortOrders: they all returned 0, but because the objects are not
        // equal, we cannot return 0.

        // Example: This might occur when sorting by [color, price] and two or more different items have the same color
        // and the same price. Because a third - tie-breaking - sort order was not supplied, the sort order of items
        // which have the same color and price, is unspecified.

        // Although the business sort order is now unspecified, the comparator contract still requires that
        //                              if comparator.compare(o1, o2) == -1, then it should be the case that
        //                                 comparator.compare(o2, o1) == +1.
        // Hash codes provide the first tie-breaker. Identity-based sequence numbers provide an exact total order when
        // unequal objects also have equal hash codes. The sequence numbers are held via weak identity keys, so a
        // long-lived comparator does not pin the objects it compared; an id is stable for as long as its object lives.
        int hashComparison = Integer.compare(o1.hashCode(), o2.hashCode());
        return hashComparison != 0
                ? hashComparison
                : Long.compare(tieBreakerId(o1), tieBreakerId(o2));
    }

    private long tieBreakerId(O object) {
        synchronized (tieBreakerLock) {
            expungeStaleTieBreakerIds();
            Long id = tieBreakerIds.get(new TieBreakerKey(object, null));
            if (id == null) {
                id = nextTieBreakerId++;
                tieBreakerIds.put(new TieBreakerKey(object, staleTieBreakerKeys), id);
            }
            return id;
        }
    }

    private void expungeStaleTieBreakerIds() {
        Reference<?> staleKey;
        while ((staleKey = staleTieBreakerKeys.poll()) != null) {
            // Removal relies on HashMap's reference-equality fast path: the enqueued key IS the stored key.
            tieBreakerIds.remove(staleKey);
        }
    }

    int retainedTieBreakerCount() {
        synchronized (tieBreakerLock) {
            expungeStaleTieBreakerIds();
            return tieBreakerIds.size();
        }
    }

    <A extends Comparable<A>> int compareAttributeValues(Attribute<O, A> attribute, O o1, O o2) {
        if (attribute instanceof SimpleAttribute) {
            // Fast code path...
            SimpleAttribute<O, A> simpleAttribute = (SimpleAttribute<O, A>)attribute;
            return simpleAttribute.getValue(o1, queryOptions).compareTo(simpleAttribute.getValue(o2, queryOptions));
        }
        else {
            // Slower code path...
            Iterator<A> o1attributeValues = attribute.getValues(o1, queryOptions).iterator();
            Iterator<A> o2attributeValues = attribute.getValues(o2, queryOptions).iterator();
            while (o1attributeValues.hasNext() && o2attributeValues.hasNext()) {
                A o1attributeValue = o1attributeValues.next();
                A o2attributeValue = o2attributeValues.next();
                int comparison = o1attributeValue.compareTo(o2attributeValue);
                if (comparison != 0) {
                    // If we found a difference, return it...
                    return comparison;
                }
            }
            // If the number of attribute values differs, return a difference, object with fewest elements first...
            if (o2attributeValues.hasNext()) {
                return -1;
            }
            else if (o1attributeValues.hasNext()) {
                return +1;
            }
            // No differences found...
            return 0;
        }
    }

    static final class TieBreakerKey extends WeakReference<Object> {

        final int identityHashCode;

        TieBreakerKey(Object referent, ReferenceQueue<Object> queue) {
            super(referent, queue);
            this.identityHashCode = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return identityHashCode;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof TieBreakerKey)) {
                return false;
            }
            Object referent = get();
            return referent != null && referent == ((TieBreakerKey) other).get();
        }
    }
}
