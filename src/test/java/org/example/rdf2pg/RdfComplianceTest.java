package org.example.rdf2pg;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RdfComplianceTest {

    @Test
    void reifiedOnlyDoesNotAssertEdge() throws Exception {
        String nt = """
                VERSION "1.2"
                _:r1 <http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies> <<( <http://ex/s> <http://ex/p> <http://ex/o> )>> .
                """;

        Model model = ModelFactory.createDefaultModel();
        NtRdf12Compat.readNtString(model, nt);
        Map<TripleKey, String> reifiers = Map.of(
                new TripleKey("http://ex/s", "http://ex/p", "U:http://ex/o"), "_:r1");
        PropertyGraph pg = new RdfToPropertyGraphTranslator().translate(model, reifiers);
        assertEquals(1, pg.getEdgeCount());
        PropertyGraphEdge edge = pg.getEdges().get(0);
        assertTrue(edge.isReified());
        assertFalse(edge.isAsserted());
        assertEquals("_:r1", edge.getReifierId());
    }

    @Test
    void assertedAndReifiedMergeSingleEdge() throws Exception {
        String nt = """
                VERSION "1.2"
                _:r1 <http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies> <<( <http://ex/s> <http://ex/p> <http://ex/o> )>> .
                <http://ex/s> <http://ex/p> <http://ex/o> .
                """;

        Model model = ModelFactory.createDefaultModel();
        NtRdf12Compat.readNtString(model, nt);
        PropertyGraph pg = new RdfToPropertyGraphTranslator().translate(model);
        assertEquals(1, pg.getEdgeCount());
        PropertyGraphEdge edge = pg.getEdges().get(0);
        assertTrue(edge.isReified());
        assertTrue(edge.isAsserted());
    }

    @Test
    void exportEmitsAssertedTripleAndPreservesReifier(@TempDir Path dir) throws Exception {
        PropertyGraph pg = new PropertyGraph();
        pg.getOrCreateNode("http://ex/s");
        pg.getOrCreateNode("http://ex/o");
        PropertyGraphEdge edge = new PropertyGraphEdge("http://ex/s", "http://ex/o", "http://ex/p");
        edge.setAsserted(true);
        edge.setReified(true);
        edge.setReifierId("_:orig");
        pg.addEdge(edge);

        Rdf12ExportBundle bundle = new PropertyGraphToRdf12Translator().translate(pg);
        assertEquals(1, bundle.getAssertedTriples().size());
        assertEquals(1, bundle.getReificationBlocks().size());
        assertEquals("_:orig", bundle.getReificationBlocks().get(0).getReifierId());

        Path out = dir.resolve("out.nt");
        Rdf12NtWriter.writeExport(bundle, out);
        String content = Files.readString(out);
        assertTrue(content.contains("<http://ex/s> <http://ex/p> <http://ex/o> ."));
        assertTrue(content.contains("_:orig"));
    }

    @Test
    void literalLangRoundTrip() {
        LiteralValue lit = LiteralTermParser.parse("\"hello\"@en");
        assertEquals("hello", lit.getLex());
        assertEquals("en", lit.getLang());
        assertEquals(Rdf12NtWriter.formatLiteralTerm(lit), "\"hello\"@en");
    }

    @Test
    void rdfTypeLabelsExportedAsAssertions() {
        PropertyGraph pg = new PropertyGraph();
        PropertyGraphNode n = pg.getOrCreateNode("http://ex/s");
        n.addLabel("http://ex/TypeA");
        Rdf12ExportBundle bundle = new PropertyGraphToRdf12Translator().translate(pg);
        assertEquals(1, bundle.getAssertedTriples().size());
        assertTrue(bundle.getAssertedTriples().get(0).contains(PgUris.RDF_TYPE));
        assertTrue(bundle.getAssertedTriples().get(0).contains("http://ex/TypeA"));
    }

    @Test
    void predicateEncodeDecodeIsLossless() {
        String uri = "http://schema.org/knows";
        String enc = PgUris.encodePredicateUri(uri);
        assertEquals(uri, PgUris.decodePredicateUri(enc));
    }

    @Test
    void rejectsMultipleTripleTermsPerReifier() throws Exception {
        String nt = """
                VERSION "1.2"
                _:r1 <http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies> <<( <http://ex/s1> <http://ex/p> <http://ex/o1> )>> .
                _:r1 <http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies> <<( <http://ex/s2> <http://ex/p> <http://ex/o2> )>> .
                """;

        Model model = ModelFactory.createDefaultModel();
        NtRdf12Compat.readNtString(model, nt);
        InputProfileViolationException ex = assertThrows(
                InputProfileViolationException.class,
                () -> new RdfToPropertyGraphTranslator().translate(model));
        assertTrue(ex.getMessage().contains("more than one triple term"));
    }

    @Test
    void rejectsMultipleReifiersPerTripleTerm() throws Exception {
        String nt = """
                VERSION "1.2"
                _:r1 <http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies> <<( <http://ex/s> <http://ex/p> <http://ex/o> )>> .
                _:r2 <http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies> <<( <http://ex/s> <http://ex/p> <http://ex/o> )>> .
                """;

        Model model = ModelFactory.createDefaultModel();
        NtRdf12Compat.readNtString(model, nt);
        InputProfileViolationException ex = assertThrows(
                InputProfileViolationException.class,
                () -> new RdfToPropertyGraphTranslator().translate(model));
        assertTrue(ex.getMessage().contains("more than one reifier"));
    }

    @Test
    void rejectsReifiedRdfType() throws Exception {
        String nt = """
                VERSION "1.2"
                _:r1 <http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies> <<( <http://ex/s> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://ex/TypeA> )>> .
                """;

        Model model = ModelFactory.createDefaultModel();
        NtRdf12Compat.readNtString(model, nt);
        InputProfileViolationException ex = assertThrows(
                InputProfileViolationException.class,
                () -> new RdfToPropertyGraphTranslator().translate(model));
        assertTrue(ex.getMessage().contains("Reified rdf:type"));
    }

    @Test
    void mergeLabelUrisCombinesWithoutDuplicates() {
        List<String> merged = StreamingRdf12ToNeo4jImporter.mergeLabelUris(
                List.of("http://ex/A", "http://ex/B"),
                List.of("http://ex/B", "http://ex/C"));
        assertEquals(List.of("http://ex/A", "http://ex/B", "http://ex/C"), merged);
    }

    @Test
    void multilineLiteralEscapesInNtExport(@TempDir Path dir) throws Exception {
        ReificationBlock block = new ReificationBlock(
                "http://ex/s",
                PgUris.propertyUri("description"),
                null,
                new LiteralValue("line one\nline two\"quote", PgUris.XSD_STRING, null, null),
                Map.of(),
                null,
                false,
                true);
        Path out = dir.resolve("out.nt");
        Rdf12NtWriter.writeBlocks(java.util.List.of(block), out);
        String content = Files.readString(out);
        assertFalse(content.contains("line one\nline two"), "raw newline must not appear in NT file");
        assertTrue(content.contains("\\n"), content);
        assertTrue(content.contains("\\\"quote"), content);
        assertEquals(2, Files.readAllLines(out).size(), "VERSION + single physical NT line for block");
    }

    @Test
    void canonicalCompareMergesNonContiguousReifierAnnotations(@TempDir Path dir) throws Exception {
        Path a = dir.resolve("a.nt");
        Path b = dir.resolve("b.nt");
        Files.writeString(a, """
                VERSION "1.2"
                _:r1 <http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies> <<( <http://ex/s> <http://ex/p> <http://ex/o1> )>> .
                _:r1 <http://schema.org/startDate> "1993"^^<http://www.w3.org/2001/XMLSchema#gYear> .
                _:r2 <http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies> <<( <http://ex/s> <http://ex/p> <http://ex/o2> )>> .
                _:r1 <http://schema.org/startDate> "2007"^^<http://www.w3.org/2001/XMLSchema#gYear> .
                """);
        Files.writeString(b, """
                VERSION "1.2"
                _:r1 <http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies> <<( <http://ex/s> <http://ex/p> <http://ex/o1> )>> .
                _:r1 <http://schema.org/startDate> "1993"^^<http://www.w3.org/2001/XMLSchema#gYear> .
                _:r1 <http://schema.org/startDate> "2007"^^<http://www.w3.org/2001/XMLSchema#gYear> .
                _:r2 <http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies> <<( <http://ex/s> <http://ex/p> <http://ex/o2> )>> .
                """);
        StreamingCanonicalNtComparer.CompareResult result =
                StreamingCanonicalNtComparer.compareCanonicalFiles(a, b);
        assertTrue(result.equal, () -> "onlyA=" + result.onlyInA + " onlyB=" + result.onlyInB);
    }

    @Test
    void canonicalCompareIgnoresDuplicateAnnotationLines(@TempDir Path dir) throws Exception {
        Path a = dir.resolve("a.nt");
        Path b = dir.resolve("b.nt");
        Files.writeString(a, """
                VERSION "1.2"
                _:r1 <http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies> <<( <http://ex/s> <http://ex/p> <http://ex/o> )>> .
                _:r1 <http://schema.org/startDate> "2020"^^<http://www.w3.org/2001/XMLSchema#gYear> .
                _:r1 <http://schema.org/startDate> "2020"^^<http://www.w3.org/2001/XMLSchema#gYear> .
                """);
        Files.writeString(b, """
                VERSION "1.2"
                _:r1 <http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies> <<( <http://ex/s> <http://ex/p> <http://ex/o> )>> .
                _:r1 <http://schema.org/startDate> "2020"^^<http://www.w3.org/2001/XMLSchema#gYear> .
                """);
        StreamingCanonicalNtComparer.CompareResult result =
                StreamingCanonicalNtComparer.compareCanonicalFiles(a, b);
        assertTrue(result.equal, () -> "onlyA=" + result.onlyInA + " onlyB=" + result.onlyInB);
    }

    @Test
    void canonicalCompareIgnoresReifierId(@TempDir Path dir) throws Exception {
        Path a = dir.resolve("a.nt");
        Path b = dir.resolve("b.nt");
        Files.writeString(a, """
                VERSION "1.2"
                _:r1 <http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies> <<( <http://ex/s> <http://ex/p> <http://ex/o> )>> .
                """);
        Files.writeString(b, """
                VERSION "1.2"
                _:r2 <http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies> <<( <http://ex/s> <http://ex/p> <http://ex/o> )>> .
                """);
        Set<String> ca = StreamingCanonicalNtComparer.canonicalLines(a);
        Set<String> cb = StreamingCanonicalNtComparer.canonicalLines(b);
        assertEquals(ca, cb);
    }
}
