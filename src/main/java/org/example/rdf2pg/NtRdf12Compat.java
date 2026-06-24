package org.example.rdf2pg;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Wczytywanie N-Triples zgodnych z RDF 1.2: pomija wiodące dyrektywy {@code VERSION "1.2"}
 * oraz puste linie i komentarze, których domyślny parser Jena (Lang.NTRIPLES) nie akceptuje.
 */
public final class NtRdf12Compat {

    private NtRdf12Compat() {}

    public static void readIntoModel(Model model, Path path) throws IOException {
        try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            readFromReader(model, br);
        }
    }

    public static void readNtString(Model model, String nt) throws IOException {
        try (BufferedReader br = new BufferedReader(new StringReader(nt))) {
            readFromReader(model, br);
        }
    }

    private static void readFromReader(Model model, BufferedReader br) throws IOException {
            String firstDataLine = null;
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                if (t.length() >= 7 && t.regionMatches(true, 0, "VERSION", 0, 7)) {
                    continue;
                }
                firstDataLine = line;
                break;
            }
            if (firstDataLine == null) {
                return;
            }
            try (Reader composed = new ChainedReader(new StringReader(firstDataLine + "\n"), br)) {
                RDFParser.create().source(composed).lang(Lang.NTRIPLES).parse(model);
            }
    }

    private static final class ChainedReader extends Reader {
        private Reader first;
        private final Reader second;
        private boolean onFirst = true;

        ChainedReader(Reader first, Reader second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public int read(char[] cbuf, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (onFirst) {
                int n = first.read(cbuf, off, len);
                if (n > 0) {
                    return n;
                }
                onFirst = false;
                first.close();
                first = null;
            }
            return second.read(cbuf, off, len);
        }

        @Override
        public void close() throws IOException {
            if (onFirst && first != null) {
                first.close();
            }
            second.close();
        }
    }
}
