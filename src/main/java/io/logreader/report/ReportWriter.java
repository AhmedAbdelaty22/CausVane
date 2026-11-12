package io.logreader.report;

import io.logreader.model.AnalysisResult;
import io.logreader.model.ErrorGroup;

import java.io.IOException;
import java.util.List;

/** Serialises an analysis into a shareable report. */
public interface ReportWriter {

    /**
     * @param groups the errors to include (typically the filtered view), in display order
     */
    void write(AnalysisResult result, List<ErrorGroup> groups, Appendable out) throws IOException;

    /** Available report formats. */
    enum Format {
        MARKDOWN("md", "Markdown"),
        JSON("json", "JSON");

        private final String extension;
        private final String label;

        Format(String extension, String label) {
            this.extension = extension;
            this.label = label;
        }

        public String extension() {
            return extension;
        }

        public String label() {
            return label;
        }

        public ReportWriter writer() {
            return switch (this) {
                case MARKDOWN -> new MarkdownReportWriter();
                case JSON -> new JsonReportWriter();
            };
        }
    }
}
