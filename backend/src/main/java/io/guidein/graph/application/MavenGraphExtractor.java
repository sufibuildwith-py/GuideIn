package io.guidein.graph.application;

import io.guidein.graph.api.GraphModel.*;
import javax.xml.stream.*;
import java.io.StringReader;
import java.util.*;

/** Reads raw declarations only. It never builds an effective model or resolves artifacts. */
public final class MavenGraphExtractor implements GraphExtractor {
    private record Element(String name, String text, List<Element> children, int line, int column) {
        Element child(String name) { return children.stream().filter(e -> e.name.equals(name)).findFirst().orElse(null); }
        String value(String name) { var e = child(name); return e == null ? "" : e.text.strip(); }
        List<Element> all(String name) { return children.stream().filter(e -> e.name.equals(name)).toList(); }
        String locator() { return "line:" + line + ":column:" + column; }
    }
    private static final class Frame {
        final String name; final StringBuilder text = new StringBuilder(); final List<Element> children = new ArrayList<>(); final int line, column;
        Frame(XMLStreamReader r) { name = r.getLocalName(); line = r.getLocation().getLineNumber(); column = r.getLocation().getColumnNumber(); }
        Element finish() { return new Element(name, text.toString(), List.copyOf(children), line, column); }
    }
    private record Pom(String path, Element root, String group, String artifact) { String module() { return "module:" + SourcePaths.directory(path); } }
    @Override public String version() { return "maven-v1"; }
    private Element parse(String text, int nesting) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> { throw new XMLStreamException("External entity denied"); });
        XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(text));
        ArrayDeque<Frame> stack = new ArrayDeque<>(); Element root = null;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) throw new XMLStreamException("DTD denied");
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if (stack.size() >= nesting) throw new XMLStreamException("Depth limit");
                    stack.push(new Frame(reader));
                } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                    if (!stack.isEmpty()) stack.peek().text.append(reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    Element finished = stack.pop().finish();
                    if (stack.isEmpty()) root = finished; else stack.peek().children.add(finished);
                }
            }
        } finally { reader.close(); }
        if (root == null || !root.name.equals("project")) throw new XMLStreamException("Project required");
        return root;
    }
    @Override public void extract(SourceMaterial material, GraphLimits limits, GraphFacts facts) {
        facts.source(Source.MAVEN);
        TreeMap<String, Pom> poms = new TreeMap<>(); Map<String, List<Pom>> coordinates = new TreeMap<>();
        for (String path : material.paths()) {
            if (!(path.equals("pom.xml") || path.endsWith("/pom.xml"))) continue;
            try {
                Element root = parse(material.text(path, limits.manifestBytes()), limits.nesting());
                String group = root.value("groupId"), artifact = root.value("artifactId");
                Element parent = root.child("parent");
                if (group.isEmpty() && parent != null) group = parent.value("groupId");
                Pom pom = new Pom(path, root, group, artifact); poms.put(path, pom);
                if (!group.isBlank() && !artifact.isBlank() && !group.contains("${") && !artifact.contains("${"))
                    coordinates.computeIfAbsent(group + ":" + artifact, ignored -> new ArrayList<>()).add(pom);
                else facts.gap("MAVEN_PROPERTY_UNRESOLVED", path, "coordinates");
                facts.node(pom.module(), NodeType.MODULE, artifact, path, Map.of("groupId", group, "artifactId", artifact,
                        "packaging", root.value("packaging").isEmpty() ? "jar" : root.value("packaging"), "model", "RAW_EXPLICIT"));
                if (!pom.module().equals("module:.")) facts.edge("repo:root", EdgeType.CONTAINS, pom.module(),
                        facts.evidence(Source.MAVEN, material, path, root.locator(), "MODULE_DECLARATION"));
                if (root.child("profiles") != null) facts.gap("MAVEN_PROFILE_DYNAMIC", path, "profiles");
                if (root.child("dependencyManagement") != null) facts.gap("MAVEN_EFFECTIVE_MODEL_UNRESOLVED", path, "dependencyManagement");
            } catch (XMLStreamException | IllegalArgumentException ex) { facts.gap("MAVEN_INVALID", path, "document"); }
        }
        for (Pom pom : poms.values()) {
            Element parent = pom.root.child("parent");
            if (parent != null) {
                String coordinate = parent.value("groupId") + ":" + parent.value("artifactId");
                List<Pom> matches = coordinates.getOrDefault(coordinate, List.of());
                if (matches.size() != 1 || matches.getFirst().path.equals(pom.path)) facts.gap("MAVEN_PARENT_UNRESOLVED", pom.path, parent.locator());
                else {
                    // The parent declaration is an explicit relation, not a claim that inheritance was evaluated.
                    facts.edge(pom.module(), EdgeType.DEPENDS_ON, matches.getFirst().module(),
                            facts.evidence(Source.MAVEN, material, pom.path, parent.locator(), "DECLARED_PARENT"));
                }
            }
            Element modules = pom.root.child("modules");
            if (modules != null) for (Element module : modules.all("module")) {
                try {
                    String directory = SourcePaths.directory(pom.path);
                    String target = SourcePaths.normalize((directory.equals(".") ? "" : directory + "/") + module.text.strip() + "/pom.xml");
                    Pom child = poms.get(target);
                    if (child == null) facts.gap("MAVEN_MODULE_UNRESOLVED", pom.path, module.locator());
                    else facts.edge(pom.module(), EdgeType.CONTAINS, child.module(), facts.evidence(Source.MAVEN, material, pom.path, module.locator(), "DECLARED_MODULE"));
                } catch (IllegalArgumentException ex) { facts.gap("MAVEN_MODULE_PATH_REJECTED", pom.path, module.locator()); }
            }
            Element dependencies = pom.root.child("dependencies");
            if (dependencies == null) continue;
            for (Element dependency : dependencies.all("dependency")) {
                String group = dependency.value("groupId"), artifact = dependency.value("artifactId");
                String coordinate = group + ":" + artifact;
                if (group.isBlank() || artifact.isBlank() || coordinate.contains("${")) {
                    facts.gap("MAVEN_PROPERTY_UNRESOLVED", pom.path, dependency.locator()); continue;
                }
                List<Pom> matches = coordinates.getOrDefault(coordinate, List.of());
                if (matches.size() > 1) { facts.gap("MAVEN_AMBIGUOUS_COORDINATE", pom.path, dependency.locator()); continue; }
                String target;
                if (matches.size() == 1) target = matches.getFirst().module();
                else {
                    target = "external:maven:" + coordinate;
                    facts.node(target, NodeType.EXTERNAL_PROVIDER, coordinate, "", Map.of("ecosystem", "maven"));
                }
                var base = facts.evidence(Source.MAVEN, material, pom.path, dependency.locator(), "DECLARED_DEPENDENCY");
                String scope = dependency.value("scope").isEmpty() ? "compile" : dependency.value("scope");
                var ev = new Evidence(base.source(), base.path(), base.locator(), base.digest(), base.extractorVersion(), base.observation(), base.trust(), base.confidence(),
                        Map.of("scope", scope, "version", dependency.value("version"), "model", "RAW_EXPLICIT"));
                facts.edge(pom.module(), EdgeType.DEPENDS_ON, target, ev);
                if (dependency.value("version").contains("${")) facts.gap("MAVEN_PROPERTY_UNRESOLVED", pom.path, dependency.locator());
            }
        }
    }
}
