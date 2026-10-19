package dev.atlas.intelligence;

import java.util.List;

/** An optional explanation adapter, with no file, command, network, or mutation tools. */
public interface ModelProvider {
    record Context(
        String id,
        String path,
        int startLine,
        int endLine,
        String text
    ) {}

    record Completion(String answer, List<String> citationIds) {}

    Completion explain(String question, List<Context> context) throws Exception;
    boolean configured();
}
