/** RdfDefinitionsLoader.java
 *
 * Parses RDF/OWL definition files (RDF/XML, Turtle, or JSON-LD) into Jena models, guessing the
 * serialization from the file extension with format-agnostic fallback.
 *
 * Copyright 2026 President and Fellows of Harvard College
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
 *
 */
package org.filteredpush.bdq_workbench.rdf_policy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RiotException;
import org.filteredpush.bdq_workbench.app.AppException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads RDF/OWL definition files into Jena {@link Model}s.
 *
 * <p>Extracted from {@link RdfPolicyResolverService} so that other consumers of the same kind of
 * RDF definition files (RDF/XML, Turtle, or JSON-LD — for example {@code bdqtest.ttl} or
 * {@code bdqffdq.owl}, as referenced by {@link org.filteredpush.bdq_workbench.app.AppConfig#rdfDefinitions()})
 * can parse them the same way without duplicating the format-detection logic.
 */
public final class RdfDefinitionsLoader {
    private static final Logger LOG = LoggerFactory.getLogger(RdfDefinitionsLoader.class);

    private RdfDefinitionsLoader() {
    }

    /**
     * Parses each of the given paths and merges them into a single model, skipping any path that
     * does not exist.
     *
     * @param paths RDF definition files to load and merge
     * @return a single model containing the union of all parsed files' statements
     * @throws AppException if a file that exists cannot be read or parsed as RDF
     */
    public static Model load(List<Path> paths) {
        Model model = ModelFactory.createDefaultModel();
        for (Path path : paths) {
            if (!Files.exists(path)) {
                LOG.debug("Skipping missing RDF definitions file: {}", path);
                continue;
            }
            LOG.debug("Loading RDF definitions from {}", path.toAbsolutePath());
            model.add(readIntoModel(path));
        }
        return model;
    }

    /**
     * Parses a single RDF definition file into its own model, guessing the serialization from the
     * file extension and falling back to the other supported serializations if that guess fails to
     * parse.
     *
     * @param path the RDF definition file to parse; must exist
     * @return a new model containing the file's statements
     * @throws AppException if the file cannot be read, or cannot be parsed as RDF/XML, Turtle, or
     *     JSON-LD
     */
    public static Model readIntoModel(Path path) {
        List<Lang> languages = orderedLangCandidates(path);
        RiotException lastRiot = null;
        IOException lastIo = null;
        for (Lang lang : languages) {
            try (InputStream in = Files.newInputStream(path)) {
                LOG.debug("Parsing RDF definitions file {} as {}", path.toAbsolutePath(), lang.getName());
                Model model = ModelFactory.createDefaultModel();
                RDFParser.source(in)
                        .base(path.toUri().toString())
                        .lang(lang)
                        .parse(model);
                return model;
            } catch (RiotException e) {
                lastRiot = e;
            } catch (IOException e) {
                lastIo = e;
            }
        }
        if (lastIo != null) {
            throw new AppException("Unable to read RDF definitions from " + path, lastIo);
        }
        throw new AppException("Unable to parse RDF definitions from " + path, lastRiot);
    }

    /**
     * Orders the RDF serializations to attempt parsing a file as, guessed from its file extension
     * and falling back to trying the other supported serializations if the guessed one fails.
     *
     * @param path the file whose extension should be inspected
     * @return the serializations to try, in order
     */
    private static List<Lang> orderedLangCandidates(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".xml") || name.endsWith(".rdf") || name.endsWith(".owl")) {
            return List.of(Lang.RDFXML, Lang.TURTLE, Lang.JSONLD);
        }
        if (name.endsWith(".ttl")) {
            return List.of(Lang.TURTLE, Lang.RDFXML, Lang.JSONLD);
        }
        if (name.endsWith(".jsonld") || name.endsWith(".json")) {
            return List.of(Lang.JSONLD, Lang.TURTLE, Lang.RDFXML);
        }
        return List.of(Lang.TURTLE, Lang.RDFXML, Lang.JSONLD);
    }
}
