/*
 * Copyright Terracotta, Inc.
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

package org.ehcache.impl.internal.store.disk;

import org.ehcache.config.Eviction;
import org.ehcache.config.EvictionVeto;
import org.ehcache.function.BiFunction;
import org.ehcache.impl.internal.store.offheap.HeuristicConfiguration;
import org.ehcache.impl.internal.store.offheap.factories.EhcacheSegmentFactory;
import org.ehcache.impl.internal.store.offheap.factories.EhcacheSegmentFactory.EhcacheSegment.EvictionListener;
import org.ehcache.impl.internal.store.offheap.portability.SerializerPortability;
import org.ehcache.impl.internal.spi.serialization.DefaultSerializationProvider;
import org.ehcache.spi.serialization.SerializationProvider;
import org.ehcache.spi.serialization.Serializer;
import org.ehcache.spi.serialization.UnsupportedTypeException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.terracotta.offheapstore.disk.paging.MappedPageSource;
import org.terracotta.offheapstore.disk.persistent.PersistentPortability;
import org.terracotta.offheapstore.disk.storage.FileBackedStorageEngine;
import org.terracotta.offheapstore.util.Factory;

import java.io.IOException;

import static org.ehcache.impl.internal.store.disk.OffHeapDiskStore.persistent;
import static org.ehcache.impl.internal.spi.TestServiceProvider.providerContaining;
import org.ehcache.impl.internal.store.disk.factories.EhcachePersistentSegmentFactory;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

public class EhcachePersistentConcurrentOffHeapClockCacheTest {

  @Rule
  public final TemporaryFolder folder = new TemporaryFolder();

  private EhcachePersistentConcurrentOffHeapClockCache<String, String> createTestSegment() throws IOException {
    return createTestSegment(Eviction.<String, String>none(), mock(EvictionListener.class));
  }

  private EhcachePersistentConcurrentOffHeapClockCache<String, String> createTestSegment(EvictionVeto<String, String> evictionPredicate) throws IOException {
    return createTestSegment(evictionPredicate, mock(EvictionListener.class));
  }

  private EhcachePersistentConcurrentOffHeapClockCache<String, String> createTestSegment(EvictionListener<String, String> evictionListener) throws IOException {
    return createTestSegment(Eviction.<String, String>none(), evictionListener);
  }

  private EhcachePersistentConcurrentOffHeapClockCache<String, String> createTestSegment(EvictionVeto<String, String> evictionPredicate, EvictionListener<String, String> evictionListener) throws IOException {
    try {
      HeuristicConfiguration configuration = new HeuristicConfiguration(1024 * 1024);
      SerializationProvider serializationProvider = new DefaultSerializationProvider(null);
      serializationProvider.start(providerContaining());
      MappedPageSource pageSource = new MappedPageSource(folder.newFile(), true, configuration.getMaximumSize());
      Serializer<String> keySerializer = serializationProvider.createKeySerializer(String.class, EhcachePersistentConcurrentOffHeapClockCacheTest.class.getClassLoader());
      Serializer<String> valueSerializer = serializationProvider.createValueSerializer(String.class, EhcachePersistentConcurrentOffHeapClockCacheTest.class.getClassLoader());
      PersistentPortability<String> keyPortability = persistent(new SerializerPortability<String>(keySerializer));
      PersistentPortability<String> elementPortability = persistent(new SerializerPortability<String>(valueSerializer));
      Factory<FileBackedStorageEngine<String, String>> storageEngineFactory = FileBackedStorageEngine.createFactory(pageSource, keyPortability, elementPortability);
      EhcachePersistentSegmentFactory<String, String> segmentFactory = new EhcachePersistentSegmentFactory<String, String>(pageSource, storageEngineFactory, 0, evictionPredicate, evictionListener, true);
      return new EhcachePersistentConcurrentOffHeapClockCache<String, String>(evictionPredicate, segmentFactory, 1);
    } catch (UnsupportedTypeException e) {
      throw new AssertionError(e);
    }
  }

  @Test
  public void testComputeFunctionCalledWhenNoMapping() throws IOException {
    EhcachePersistentConcurrentOffHeapClockCache<String, String> segment = createTestSegment();
    try {
      String value = segment.compute("key", new BiFunction<String, String, String>() {
        @Override
        public String apply(String s, String s2) {
          return "value";
        }
      }, false);
      assertThat(value, is("value"));
      assertThat(segment.get("key"), is(value));
    } finally {
      segment.destroy();
    }
  }

  @Test
  public void testComputeFunctionReturnsSameNoPin() throws IOException {
    EhcachePersistentConcurrentOffHeapClockCache<String, String> segment = createTestSegment();
    try {
      segment.put("key", "value");
      String value = segment.compute("key", new BiFunction<String, String, String>() {
        @Override
        public String apply(String s, String s2) {
          return s2;
        }
      }, false);
      assertThat(value, is("value"));
      assertThat(segment.isPinned("key"), is(false));
    } finally {
      segment.destroy();
    }
  }

  @Test
  public void testComputeFunctionReturnsSamePins() throws IOException {
    EhcachePersistentConcurrentOffHeapClockCache<String, String> segment = createTestSegment();
    try {
      segment.put("key", "value");
      String value = segment.compute("key", new BiFunction<String, String, String>() {
        @Override
        public String apply(String s, String s2) {
          return s2;
        }
      }, true);
      assertThat(value, is("value"));
      assertThat(segment.isPinned("key"), is(true));
    } finally {
      segment.destroy();
    }
  }

  @Test
  public void testComputeFunctionReturnsSamePreservesPinWhenNoPin() throws IOException {
    EhcachePersistentConcurrentOffHeapClockCache<String, String> segment = createTestSegment();
    try {
      segment.putPinned("key", "value");
      String value = segment.compute("key", new BiFunction<String, String, String>() {
        @Override
        public String apply(String s, String s2) {
          return s2;
        }
      }, false);
      assertThat(value, is("value"));
      assertThat(segment.isPinned("key"), is(true));
    } finally {
      segment.destroy();
    }
  }

  @Test
  public void testComputeFunctionReturnsDifferentNoPin() throws IOException {
    EhcachePersistentConcurrentOffHeapClockCache<String, String> segment = createTestSegment();
    try {
      segment.put("key", "value");
      String value = segment.compute("key", new BiFunction<String, String, String>() {
        @Override
        public String apply(String s, String s2) {
          return "otherValue";
        }
      }, false);
      assertThat(value, is("otherValue"));
      assertThat(segment.isPinned("key"), is(false));
    } finally {
      segment.destroy();
    }
  }

  @Test
  public void testComputeFunctionReturnsDifferentPins() throws IOException {
    EhcachePersistentConcurrentOffHeapClockCache<String, String> segment = createTestSegment();
    try {
      segment.put("key", "value");
      String value = segment.compute("key", new BiFunction<String, String, String>() {
        @Override
        public String apply(String s, String s2) {
          return "otherValue";
        }
      }, true);
      assertThat(value, is("otherValue"));
      assertThat(segment.isPinned("key"), is(true));
    } finally {
      segment.destroy();
    }
  }

  @Test
  public void testComputeFunctionReturnsDifferentClearsPin() throws IOException {
    EhcachePersistentConcurrentOffHeapClockCache<String, String> segment = createTestSegment();
    try {
      segment.putPinned("key", "value");
      String value = segment.compute("key", new BiFunction<String, String, String>() {
        @Override
        public String apply(String s, String s2) {
          return "otherValue";
        }
      }, false);
      assertThat(value, is("otherValue"));
      assertThat(segment.isPinned("key"), is(false));
    } finally {
      segment.destroy();
    }
  }

  @Test
  public void testComputeFunctionReturnsNullRemoves() throws IOException {
    EhcachePersistentConcurrentOffHeapClockCache<String, String> segment = createTestSegment();
    try {
      segment.putPinned("key", "value");
      String value = segment.compute("key", new BiFunction<String, String, String>() {
        @Override
        public String apply(String s, String s2) {
          return null;
        }
      }, false);
      assertThat(value, nullValue());
      assertThat(segment.containsKey("key"), is(false));
    } finally {
      segment.destroy();
    }
  }

  @Test
  public void testComputeIfPresentNotCalledOnNotContainedKey() throws IOException {
    EhcachePersistentConcurrentOffHeapClockCache<String, String> segment = createTestSegment();
    try {
      try {
        segment.computeIfPresent("key", new BiFunction<String, String, String>() {
          @Override
          public String apply(String s, String s2) {
            throw new UnsupportedOperationException("Should not have been called!");
          }
        });
      } catch (UnsupportedOperationException e) {
        fail("Mapping function should not have been called.");
      }
    } finally {
      segment.destroy();
    }
  }

  @Test
  public void testComputeIfPresentReturnsSameValue() throws IOException {
    EhcachePersistentConcurrentOffHeapClockCache<String, String> segment = createTestSegment();
    try {
      segment.put("key", "value");
      String value = segment.computeIfPresent("key", new BiFunction<String, String, String>() {
        @Override
        public String apply(String s, String s2) {
          return s2;
        }
      });
      assertThat(segment.get("key"), is(value));
    } finally {
      segment.destroy();
    }
  }

  @Test
  public void testComputeIfPresentReturnsDifferentValue() throws IOException {
    EhcachePersistentConcurrentOffHeapClockCache<String, String> segment = createTestSegment();
    try {
      segment.put("key", "value");
      String value = segment.computeIfPresent("key", new BiFunction<String, String, String>() {
        @Override
        public String apply(String s, String s2) {
          return "newValue";
        }
      });
      assertThat(segment.get("key"), is(value));
    } finally {
      segment.destroy();
    }
  }

  @Test
  public void testComputeIfPresentReturnsNullRemovesMapping() throws IOException {
    EhcachePersistentConcurrentOffHeapClockCache<String, String> segment = createTestSegment();
    try {
      segment.put("key", "value");
      String value = segment.computeIfPresent("key", new BiFunction<String, String, String>() {
        @Override
        public String apply(String s, String s2) {
          return null;
        }
      });
      assertThat(segment.containsKey("key"), is(false));
    } finally {
      segment.destroy();
    }
  }

  @Test
  public void testPutVetoedComputesMetadata() throws IOException {
    EhcachePersistentConcurrentOffHeapClockCache<String, String> segment = createTestSegment(new EvictionVeto<String, String>() {
      @Override
      public boolean vetoes(String key, String value) {
        return "vetoed".equals(key);
      }
    });
    try {
      segment.put("vetoed", "value");
      assertThat(segment.getMetadata("vetoed", EhcacheSegmentFactory.EhcacheSegment.VETOED), is(EhcacheSegmentFactory.EhcacheSegment.VETOED));
    } finally {
      segment.destroy();
    }
  }

  @Test
  public void testPutPinnedVetoedComputesMetadata() throws IOException {
    EhcachePersistentConcurrentOffHeapClockCache<String, String> segment = createTestSegment(new EvictionVeto<String, String>() {
      @Override
      public boolean vetoes(String key, String value) {
        return "vetoed".equals(key);
      }
    });
    try {
      segment.putPinned("vetoed", "value");
      assertThat(segment.getMetadata("vetoed", EhcacheSegmentFactory.EhcacheSegment.VETOED), is(EhcacheSegmentFactory.EhcacheSegment.VETOED));
    } finally {
      segment.destroy();
    }
  }
}