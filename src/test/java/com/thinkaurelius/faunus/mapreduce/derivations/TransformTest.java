package com.thinkaurelius.faunus.mapreduce.derivations;

import com.thinkaurelius.faunus.BaseTest;
import com.thinkaurelius.faunus.FaunusVertex;
import com.tinkerpop.blueprints.Direction;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Vertex;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mrunit.mapreduce.MapReduceDriver;

import java.io.IOException;
import java.util.Map;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public class TransformTest extends BaseTest {

    MapReduceDriver<NullWritable, FaunusVertex, NullWritable, FaunusVertex, NullWritable, FaunusVertex> mapReduceDriver;

    public void setUp() throws Exception {
        mapReduceDriver = new MapReduceDriver<NullWritable, FaunusVertex, NullWritable, FaunusVertex, NullWritable, FaunusVertex>();
        mapReduceDriver.setMapper(new Transform.Map());
        mapReduceDriver.setReducer(new Reducer<NullWritable, FaunusVertex, NullWritable, FaunusVertex>());
    }

    public void testVertexPropertyUpdate() throws IOException {
        Configuration config = new Configuration();
        config.set(Transform.CLASS, Vertex.class.getName());
        config.set(Transform.FUNCTION, "{it -> it.location = 'santa fe'}");
        mapReduceDriver.withConfiguration(config);

        Map<Long, FaunusVertex> results = runWithToyGraph(ExampleGraph.TINKERGRAPH, this.mapReduceDriver);
        assertEquals(results.size(), 6);
        for (final FaunusVertex vertex : results.values()) {
            assertEquals(vertex.getProperty("location"), "santa fe");
            for (Edge edge : vertex.getEdges(Direction.BOTH)) {
                assertNull(edge.getProperty("location"));
            }
        }

        assertEquals(mapReduceDriver.getCounters().findCounter(Transform.Counters.EDGES_TRANSFORMED).getValue(), 0);
        assertEquals(mapReduceDriver.getCounters().findCounter(Transform.Counters.VERTICES_TRANSFORMED).getValue(), 6);
    }

    public void testEdgePropertyUpdate() throws IOException {
        Configuration config = new Configuration();
        config.set(Transform.CLASS, Edge.class.getName());
        config.set(Transform.FUNCTION, "{it -> it.time = 'now'}");
        mapReduceDriver.withConfiguration(config);

        Map<Long, FaunusVertex> results = runWithToyGraph(ExampleGraph.TINKERGRAPH, this.mapReduceDriver);
        assertEquals(results.size(), 6);
        for (final FaunusVertex vertex : results.values()) {
            assertNull(vertex.getProperty("time"));
            for (Edge edge : vertex.getEdges(Direction.BOTH)) {
                assertEquals(edge.getProperty("time"), "now");
            }
        }

        assertEquals(mapReduceDriver.getCounters().findCounter(Transform.Counters.EDGES_TRANSFORMED).getValue(), 12);
        assertEquals(mapReduceDriver.getCounters().findCounter(Transform.Counters.VERTICES_TRANSFORMED).getValue(), 0);
    }

}