# Dependency review — 19 October 2026

The local review used Trivy 0.76.0 from the [official release](https://github.com/aquasecurity/trivy/releases/tag/v0.76.0), with its Windows archive checked against the release checksum manifest. Archive SHA-256: `7b4762a5a5326cb4b83dfd2bb5d9aaf15a1ed867c75cad7dd8b38840a28e7be6`. Scanning runs locally; vulnerability databases are downloaded. No repository content is sent to an AI provider.

The initial source scan flagged eight High/Critical findings in the Boot 4.1.1 managed dependency graph. The initial API image scan additionally flagged Alpine's libexpat. These are scanner findings, not an assessment that every reported exploit path is reachable in ATLAS.

| Component | Initial version | Remediation |
|---|---|---|
| Tomcat embed core | 11.0.24 | Patch override `tomcat.version=11.0.27` |
| Jackson 3 core/databind | 3.1.5 | Coordinated BOM override `jackson-bom.version=3.1.8` |
| Alpine libexpat | 2.8.5-r0 | Upgrade runtime package from the image's configured Alpine repository |
| Console nginx base | 1.28 line | Move to the official current stable `1.30.6-alpine` and upgrade OS packages |

The overrides preserve the existing Tomcat 11 and Jackson 3.1 release lines. See the [Tomcat security advisories](https://tomcat.apache.org/security-11.html) and [Jackson patch release notes](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.1.7). Remove/review the overrides when Spring Boot's BOM includes equal/newer patches. The nginx stable tags were checked against [Docker's official image manifest](https://github.com/docker-library/official-images/blob/master/library/nginx).

Known initial IDs: Tomcat CVE-2026-65182, CVE-2026-65905, CVE-2026-68525; Jackson CVE-2026-89407, CVE-2026-89425, CVE-2026-68497, CVE-2026-91776, CVE-2026-91777; libexpat CVE-2026-77214.

The patched source graph and both final runtime-image scans reported zero High/Critical findings. Regression results are recorded in TESTING. This is a point-in-time check of the scanner's current database and selected severities, not proof that all dependencies or deployment settings are secure. npm audit separately covers the frontend lockfile. CI pins the scanner action commit and scanner version; GitHub execution has not been observed here.

## Full workspace continuation

The final source and both rebuilt app images were rescanned after adding OpenTelemetry SDK/BOM 1.67.0, JavaParser 3.28.2 and runtime Git. All three High/Critical gates returned zero findings; npm audit returned zero findings. The OTel BOM is overridden as a unit to avoid mixing the Boot-managed 1.62 components with SDK 1.67. Parser metadata came from Maven Central and the [JavaParser project](https://github.com/javaparser/javaparser); OTel artifacts were checked against the [official SDK repository](https://github.com/open-telemetry/opentelemetry-java). No unrelated dependencies were added for graph rendering or provider access.

The scanner checks resolved packages in app runtime images. The infrastructure PostgreSQL image, host software, browser test binaries, live model weights, and deployment configuration are outside these recorded app-image findings. Continue scanning as advisory databases and base images change.
