# Synanton Document Ingestion Processing

## Detailed Design

**Status:** Proposed
**Version:** 1.0
**Scope:** Lucentrix ingestion ETL, Synanton raw-content ingestion, document processing, processor adapters, and OpenDataLoader integration.

------

## 1. Purpose

This document defines the detailed architecture for ingesting documents from heterogeneous enterprise sources into Synanton and transforming those documents into structured content suitable for knowledge processing.

The design establishes a strict separation between:

1. **Source acquisition** - performed by Lucentrix.
2. **Raw-content preservation and provenance** - performed by the Synanton platform.
3. **Document processing** - performed by Synanton through a platform-owned processing API.
4. **PDF extraction** - performed by replaceable processor implementations such as OpenDataLoader.
5. **Knowledge processing** - performed by downstream Synanton services.

The central architectural principle is:

> **Lucentrix knows how to get the content. Synanton knows how to process the content. Processing implementations are cloaked behind Synanton-owned APIs.**

OpenDataLoader is therefore **one PDF processing implementation**, not a platform-level dependency.

------

# 2. Architecture Overview

```text
                         EXTERNAL SOURCES
                              │
          ┌───────────────────┼────────────────────┐
          │                   │                    │
       SharePoint           FileNet             Local FS
          │                   │                    │
          │                   │                    │
          └───────────────────┼────────────────────┘
                              │
                              ▼
                    ┌───────────────────┐
                    │     LUCENTRIX     │
                    │                   │
                    │ Enterprise ETL    │
                    │                   │
                    │ Discover          │
                    │ Change detection  │
                    │ Retrieve          │
                    │ Normalize         │
                    │ Describe          │
                    │ Route             │
                    └─────────┬─────────┘
                              │
                   content + source description
                              │
                              ▼
                    ┌───────────────────┐
                    │     SYNVAULT      │
                    │                   │
                    │ Raw Content       │
                    │ Provenance        │
                    │ Version           │
                    │ Source Metadata   │
                    └─────────┬─────────┘
                              │
                              ▼
                    ┌───────────────────┐
                    │ SYNANTON PIPELINE │
                    │                   │
                    │ Type detection    │
                    │ Processing route  │
                    └─────────┬─────────┘
                              │
                              ▼
              ┌────────────────────────────────┐
              │ Synanton Document Processing   │
              │ API                            │
              │                                │
              │ Platform-owned contract        │
              └───────────────┬────────────────┘
                              │
                        Adapter / Cloak
                              │
              ┌───────────────┼────────────────┐
              │               │                │
              ▼               ▼                ▼
       OpenDataLoader     Other PDF       Future PDF
          Adapter          Adapter         Adapter
              │
              ▼
       OpenDataLoader
              │
              ▼
      Structured extraction
              │
              ▼
       Canonical Document
              │
       ┌──────┼─────────┐
       ▼      ▼         ▼
    Chunking Relations Ontology
       │      │         │
       └──────┼─────────┘
              ▼
       Knowledge Platform
```

------

# 3. Architectural Responsibilities

## 3.1 Lucentrix

Lucentrix is the **enterprise ingestion ETL layer**.

Its primary responsibility is source acquisition.

Lucentrix answers:

> **How do we reliably retrieve content from this source system?**

Lucentrix is responsible for:

- source-specific authentication;
- source discovery;
- search;
- crawling;
- pagination;
- event/change feeds;
- incremental synchronization;
- create/update/delete detection;
- content retrieval;
- retry handling;
- rate limiting;
- synchronization state;
- source identity;
- source metadata;
- provenance;
- content checksums;
- normalized ingestion events.

Lucentrix is **not** responsible for:

- PDF parsing;
- OCR;
- document chunking;
- embeddings;
- semantic entity extraction;
- relationship extraction;
- ontology construction;
- knowledge indexing.

------

# 4. Source Connector Architecture

Each source-specific connector implements the Lucentrix source abstraction.

```text
SourceConnector
│
├── discover()
├── search()
├── detectChanges()
├── retrieve()
└── describe()
```

Examples:

```text
SharePointConnector
FileNetConnector
BoxConnector
LocalFilesystemConnector
S3Connector
WebConnector
DatabaseConnector
```

The connector encapsulates source-specific complexity.

For example:

```text
SharePointConnector
│
├── tenant discovery
├── site discovery
├── web discovery
├── library discovery
├── search
├── change/event handling
├── permissions
├── pagination
├── content retrieval
└── synchronization state
```

The rest of Lucentrix should not need to understand SharePoint's API model.

------

# 5. Ingestion Document

The normalized output of Lucentrix is an `IngestionDocument`.

It represents both the original content and its source description.

Conceptually:

```text
IngestionDocument
│
├── identity
│   ├── sourceSystem
│   ├── sourceObjectId
│   ├── sourceVersion
│   └── contentHash
│
├── origin
│   ├── sourceUri
│   ├── repository
│   ├── site
│   ├── project
│   └── path
│
├── content
│   ├── filename
│   ├── mediaType
│   ├── size
│   └── bytes
│
├── metadata
│   ├── title
│   ├── author
│   ├── created
│   ├── modified
│   └── sourceSpecific
│
└── change
    ├── CREATED
    ├── UPDATED
    └── DELETED
```

Example:

```json
{
  "identity": {
    "sourceSystem": "sharepoint",
    "sourceObjectId": "sp-78231",
    "sourceVersion": "17",
    "contentHash": "sha256:abc123..."
  },
  "origin": {
    "tenant": "acme",
    "site": "Engineering",
    "web": "Architecture",
    "library": "Project Documents",
    "project": "Project Alpha",
    "path": "/Architecture/system-design.pdf",
    "sourceUri": "https://..."
  },
  "content": {
    "filename": "system-design.pdf",
    "mediaType": "application/pdf",
    "size": 1829301
  },
  "metadata": {
    "title": "System Design",
    "author": "Architecture Team",
    "modified": "2026-08-23T09:42:00Z"
  },
  "change": {
    "operation": "UPDATED"
  }
}
```

------

# 6. Source Metadata and Content Metadata

The architecture deliberately distinguishes two types of metadata.

## Source metadata

Provided by Lucentrix.

It answers:

> **Where did this content come from?**

Example:

```text
sourceSystem = sharepoint
tenant = acme
site = Engineering
web = Architecture
library = Project Documents
project = Alpha
path = /Architecture/system-design.pdf
sourceObjectId = sp-78231
version = 17
modified = ...
```

## Content metadata

Produced by Synanton document processing.

It answers:

> **What is inside the document?**

For a PDF this might include:

```text
PDF title
author
page count
headings
paragraphs
tables
images
formulas
page positions
reading order
```

The two are combined but remain conceptually separate.

```text
               DOCUMENT
                   │
        ┌──────────┴──────────┐
        │                     │
        ▼                     ▼
 SOURCE DESCRIPTION      CONTENT DESCRIPTION
        │                     │
     Lucentrix           PDF Processor
        │                     │
        └──────────┬──────────┘
                   ▼
            Synanton Document
```

------

# 7. Synvault

Synvault is the raw-content persistence boundary.

Its responsibility is to preserve the source artifact and its provenance.

```text
Synvault Object
│
├── content bytes
├── content hash
├── source identity
├── origin
├── source metadata
├── version
├── ingestion timestamp
└── processing references
```

The raw document should be treated as immutable.

This provides:

- reproducible processing;
- parser replacement;
- reprocessing;
- auditing;
- version comparison;
- provenance;
- recovery.

If the PDF processor changes from OpenDataLoader to another implementation, the original document does not need to be retrieved again.

------

# 8. Ingestion Event

After successful persistence, Lucentrix/Synanton emits an ingestion event.

Example:

```json
{
  "eventType": "document.available",
  "documentId": "doc-82af...",
  "source": {
    "system": "sharepoint",
    "objectId": "sp-78231",
    "version": "17"
  },
  "content": {
    "store": "synvault",
    "objectId": "sv-19382",
    "mediaType": "application/pdf",
    "sha256": "abc123..."
  },
  "processing": {
    "pipeline": "default"
  }
}
```

The processing pipeline consumes this event rather than requiring Lucentrix to know how the document will be processed.

------

# 9. Document Processing Pipeline

The Synanton platform determines how a document should be processed.

```text
document.available
        │
        ▼
Raw Content
        │
        ▼
Content Type Detection
        │
        ├── PDF ──────► PDF processor
        ├── EPUB ─────► EPUB processor
        ├── DOCX ─────► DOCX processor
        ├── HTML ─────► HTML processor
        └── ...
```

This is an important architectural distinction:

> **Lucentrix routes content based on source concerns. Synanton routes content based on document-processing concerns.**

------

# 10. Synanton Document Processing API

Synanton owns the document-processing contract.

A conceptual API could be:

```java
public interface DocumentProcessor {

    boolean supports(ContentDescriptor content);

    ProcessingResult process(
        RawDocument document,
        ProcessingContext context
    );
}
```

The contract should remain independent of any external parser.

A processing result might contain:

```text
ProcessingResult
│
├── canonicalDocument
├── extractedMetadata
├── provenance
├── diagnostics
└── processingInfo
```

------

# 11. Processor Adapter / Cloaking Layer

The adapter is the architectural boundary between Synanton and external processing technology.

```text
                 Synanton API
                     │
                     ▼
           DocumentProcessor
                     │
                     ▼
             Adapter / Cloak
                     │
        ┌────────────┼────────────┐
        │            │            │
        ▼            ▼            ▼
   OpenDataLoader  PDFBox      Future
      Adapter      Adapter    Processor
        │
        ▼
 OpenDataLoader
```

The rest of Synanton does not know which implementation is being used.

The adapter is responsible for:

- translating the Synanton request into processor-specific input;
- configuring the external engine;
- invoking the engine;
- handling errors;
- mapping processor output;
- translating metadata;
- translating structural elements;
- preserving provenance;
- producing the Synanton canonical model.

------

# 12. OpenDataLoader Adapter

OpenDataLoader is one implementation of the PDF processing API.

```text
Synanton
   │
   ▼
PdfDocumentProcessor
   │
   ▼
OpenDataLoaderAdapter
   │
   ▼
OpenDataLoader
   │
   ▼
ODL structured representation
   │
   ▼
OpenDataLoaderAdapter
   │
   ▼
CanonicalDocument
```

The OpenDataLoader implementation should be isolated in its own module.

Conceptually:

```text
document-processing/
│
├── api/
│   ├── DocumentProcessor
│   ├── ProcessingContext
│   └── ProcessingResult
│
├── model/
│   └── CanonicalDocument
│
└── pdf/
    ├── PdfDocumentProcessor
    └── OpenDataLoaderAdapter
```

A future implementation can then be added:

```text
pdf/
├── PdfDocumentProcessor
├── OpenDataLoaderAdapter
├── AlternativePdfAdapter
└── ...
```

No changes should be required to the ingestion connectors.

------

# 13. Processor Replacement

The architecture should explicitly support this scenario.

Today:

```text
Synanton
   │
   ▼
DocumentProcessor API
   │
   ▼
OpenDataLoaderAdapter
   │
   ▼
OpenDataLoader
```

Later:

```text
Synanton
   │
   ▼
DocumentProcessor API
   │
   ▼
AlternativePdfAdapter
   │
   ▼
Alternative PDF engine
```

Or even:

```text
PDF Processor Registry
│
├── standard PDF → OpenDataLoader
├── scanned PDF → OCR processor
├── complex PDF → specialized processor
└── legacy PDF → fallback processor
```

The upstream pipeline remains unchanged.

------

# 14. Canonical Document Model

The processor adapter must map external representations into a Synanton-owned model.

Conceptually:

```text
CanonicalDocument
│
├── identity
├── metadata
├── provenance
├── pages
│   ├── sections
│   ├── headings
│   ├── paragraphs
│   ├── tables
│   ├── lists
│   ├── images
│   └── formulas
│
└── extraction
    ├── processor
    ├── processorVersion
    └── timestamp
```

OpenDataLoader's representation should never become the canonical Synanton schema.

This is the core of the cloaking approach.

------

# 15. Pipeline Example - FileNet

Consider:

```text
FileNet
│
├── Projects
│   ├── Alpha
│   │   ├── requirements.pdf
│   │   └── architecture.pdf
│   │
│   └── Beta
│       └── specification.pdf
│
└── Policies
    └── security-policy.pdf
```

### Initial synchronization

```text
FileNet
   │
   ├── Search API
   ├── Metadata API
   └── Content API
          │
          ▼
   FileNet Connector
          │
          ▼
       Lucentrix
          │
          ├── normalize metadata
          ├── calculate/verify checksum
          ├── identify version
          └── construct IngestionDocument
          │
          ▼
       Synvault
          │
          ▼
   document.available
          │
          ▼
   PDF Processor API
          │
          ▼
   OpenDataLoader Adapter
          │
          ▼
   CanonicalDocument
          │
          ▼
   Knowledge Processing
```

### Update

```text
FileNet event
     │
     ▼
UPDATE object-123
     │
     ▼
Lucentrix
     │
     ├── retrieve new version
     └── preserve source metadata
     │
     ▼
Synvault
     │
     ▼
document.updated
     │
     ▼
PDF processing
```

### Delete

```text
FileNet event
     │
     ▼
DELETE object-123
     │
     ▼
Lucentrix
     │
     ▼
document.deleted
     │
     ▼
Synanton
     │
     ├── retract derived content
     ├── retract search representation
     ├── update relationships
     └── preserve provenance/audit history
```

No PDF processor is involved in the delete path.

------

# 16. Pipeline Example - Local Books Library

Example filesystem:

```text
~/Books/
├── Programming/
│   ├── Effective Java.pdf
│   ├── Clean Architecture.pdf
│   └── Domain-Driven Design.pdf
│
├── Distributed Systems/
│   ├── Designing Data-Intensive Applications.pdf
│   └── Distributed Systems.epub
│
├── AI/
│   ├── Deep Learning.pdf
│   └── AI Engineering.epub
│
└── History/
    └── ...
```

Lucentrix watches the filesystem.

It does not care that the content is a PDF or EPUB.

```text
Filesystem
    │
    ▼
LocalFilesystemConnector
    │
    ├── scan
    ├── detect additions
    ├── detect modifications
    ├── detect deletions
    └── retrieve bytes
    │
    ▼
Lucentrix
    │
    ▼
Synvault
```

For:

```text
Books/Programming/Clean Architecture.pdf
```

the document type is subsequently detected by Synanton:

```text
Synvault
    │
    ▼
application/pdf
    │
    ▼
PDF DocumentProcessor
    │
    ▼
OpenDataLoaderAdapter
    │
    ▼
CanonicalDocument
```

For:

```text
Books/Distributed Systems/Distributed Systems.epub
```

the route is different:

```text
Synvault
    │
    ▼
application/epub+zip
    │
    ▼
EPUB DocumentProcessor
    │
    ▼
CanonicalDocument
```

The Lucentrix connector remains exactly the same.

------

# 17. Pipeline Example - SharePoint

Consider a SharePoint environment containing:

```text
Tenant
│
├── Corporate
│   ├── HR
│   ├── Finance
│   └── Legal
│
├── Engineering
│   ├── Architecture
│   ├── Platform
│   └── Infrastructure
│
└── Projects
    ├── Project Alpha
    ├── Project Beta
    └── Project Gamma
```

The connector must understand the SharePoint hierarchy.

```text
SharePoint Tenant
       │
       ▼
Sites
       │
       ▼
Webs
       │
       ▼
Libraries
       │
       ▼
Folders
       │
       ▼
Documents
```

Lucentrix owns this complexity.

### Initial load

```text
SharePoint
    │
    ▼
Discover sites
    │
    ▼
Discover webs/libraries
    │
    ▼
Search/enumerate documents
    │
    ▼
Retrieve metadata
    │
    ▼
Retrieve content
    │
    ▼
Lucentrix
    │
    ▼
Synvault
```

### Incremental operation

```text
SharePoint
    │
    ▼
Change/Event mechanism
    │
    ├── created
    ├── modified
    └── deleted
            │
            ▼
        Lucentrix
            │
            ▼
      ingestion event
```

For:

```text
Engineering / Architecture /
Project Alpha / system-design.pdf
```

Lucentrix might produce:

```json
{
  "sourceSystem": "sharepoint",
  "tenant": "acme",
  "site": "Engineering",
  "web": "Architecture",
  "library": "Project Documents",
  "project": "Project Alpha",
  "path": "/Architecture/system-design.pdf",
  "sourceObjectId": "sp-78231",
  "version": "17"
}
```

OpenDataLoader does not see this source hierarchy as part of its PDF extraction responsibility.

It receives the raw PDF.

Synanton retains both:

```text
Source provenance
        +
PDF structure
        +
Semantic knowledge
```

------

# 18. SharePoint Multi-Site Processing

The same Lucentrix deployment can synchronize multiple SharePoint sites:

```text
SharePoint
│
├── Engineering
│   ├── Architecture
│   ├── Platform
│   └── Infrastructure
│
├── Projects
│   ├── Alpha
│   ├── Beta
│   └── Gamma
│
└── Corporate
    ├── Finance
    └── Legal
```

The connector emits normalized documents:

```text
Document A
source = SharePoint
site = Engineering
project = Alpha

Document B
source = SharePoint
site = Engineering
project = Platform

Document C
source = SharePoint
site = Projects
project = Beta
```

Synanton can then use source metadata in downstream knowledge processing.

For example:

```text
Document
    │
    ├── source.site = Engineering
    ├── source.project = Alpha
    │
    └── extracted entities
            │
            ├── System
            ├── Component
            ├── Requirement
            └── Decision
```

This allows provenance to survive all the way into the knowledge model.

------

# 19. End-to-End Processing

A complete PDF ingestion flow is therefore:

```text
1. SOURCE
   SharePoint / FileNet / filesystem
          │
          ▼

2. LUCENTRIX
   Discover
   Detect change
   Retrieve
   Normalize
          │
          ▼

3. SYNVAULT
   Store original bytes
   Store source description
          │
          ▼

4. INGESTION EVENT
   document.available
          │
          ▼

5. DOCUMENT ROUTING
   Detect media type
          │
          ▼

6. SYNANTON PROCESSOR API
   Select PDF processor
          │
          ▼

7. ADAPTER / CLOAK
   Synanton API → processor-specific API
          │
          ▼

8. PDF PROCESSOR
   OpenDataLoader
          │
          ▼

9. CANONICAL DOCUMENT
   Synanton-owned representation
          │
          ▼

10. KNOWLEDGE PROCESSING
    Chunk
    Extract entities
    Extract relationships
    Map ontology
    Generate embeddings
    Index
          │
          ▼

11. KNOWLEDGE PLATFORM
```

------

# 20. Failure and Retry Boundaries

Each layer should own failures within its responsibility.

### Source failure

```text
SharePoint API unavailable
        │
        ▼
Lucentrix retry/backoff
```

The document processor is not involved.

### Storage failure

```text
Synvault unavailable
        │
        ▼
Lucentrix delivery retry
```

### Processing failure

```text
OpenDataLoader failure
        │
        ▼
Synanton processing retry
        │
        ├── retry same processor
        ├── use fallback processor
        └── quarantine document
```

### Knowledge-processing failure

```text
Chunking / ontology / indexing failure
        │
        ▼
Synanton pipeline retry
```

This separation prevents a parser failure from affecting source synchronization.

------

# 21. Idempotency

The architecture should use stable document identity and content hashes.

A useful identity is:

```text
sourceSystem
+
sourceObjectId
+
sourceVersion
```

while a content hash identifies the actual artifact:

```text
SHA-256(content)
```

This allows Synanton to distinguish:

```text
same document + same content
```

from:

```text
same document + new version
```

and:

```text
new source object + identical content
```

The latter can be detected as a potential duplicate without losing provenance.

------

# 22. Reprocessing

One of the major benefits of this architecture is independent reprocessing.

Suppose OpenDataLoader 1.x was initially used:

```text
Raw PDF
   │
   ▼
OpenDataLoader 1.x
   │
   ▼
Canonical Document
```

Later, OpenDataLoader is upgraded or replaced:

```text
Raw PDF
   │
   ▼
OpenDataLoader 2.x
   │
   ▼
Canonical Document
```

There is no need to contact SharePoint or FileNet again.

Synvault remains the stable processing input.

This is a fundamental reason for keeping raw content separate from processing.

------

# 23. Observability

Every processing stage should produce traceable execution information.

A document should be traceable through:

```text
source object
    ↓
Lucentrix ingestion
    ↓
Synvault object
    ↓
processing job
    ↓
processor implementation
    ↓
canonical document
    ↓
knowledge artifacts
```

For example:

```text
documentId = doc-82af

source:
    sharepoint/sp-78231/version-17

content:
    synvault/sv-19382

processing:
    processor = pdf
    implementation = opendataloader
    implementationVersion = ...
    processingVersion = ...
    started = ...
    completed = ...

derived:
    canonicalDocument = cd-8831
    chunks = ...
    embeddings = ...
```

This is especially important when different processor implementations coexist.

------

# 24. Security and Provenance

The original content should never be replaced by an extracted representation.

Instead:

```text
                 Original Artifact
                       │
                       ▼
                    Synvault
                       │
             ┌─────────┴─────────┐
             │                   │
             ▼                   ▼
       source metadata       processing
                                 │
                                 ▼
                          derived artifacts
```

Every derived representation should remain traceable to the original document.

For example:

```text
Chunk
  ↓
Canonical Document
  ↓
Synvault Object
  ↓
SharePoint Object
```

This allows the platform to answer:

> Where did this piece of knowledge originate?

------

# 25. Design Rules

The following rules should be treated as architectural constraints.

### Rule 1

**Lucentrix connectors never parse documents.**

They retrieve and describe them.

### Rule 2

**Lucentrix owns source-specific synchronization complexity.**

Search, crawling, events, pagination, retries and deletes belong there.

### Rule 3

**Synvault preserves original content.**

Never make the extracted representation the source of truth.

### Rule 4

**Synanton owns the Document Processing API.**

External processing engines implement the API through adapters.

### Rule 5

**OpenDataLoader is not the canonical document model.**

It is one implementation.

### Rule 6

**Processor implementations are replaceable.**

Changing PDF extraction technology should not require changing Lucentrix or downstream knowledge services.

### Rule 7

**Source provenance survives processing.**

The final knowledge representation must remain traceable to its source.

### Rule 8

**Deletes are propagated independently of document extraction.**

A delete event does not require document parsing.

------

# 26. Recommended Initial Implementation

The first implementation should remain deliberately small.

### Lucentrix

```text
SourceConnector
IngestionDocument
ChangeEvent
SynchronizationState
SynantonTarget
```

with initial connectors:

```text
LocalFilesystemConnector
FileNetConnector
SharePointConnector
```

### Synanton

```text
RawDocument
DocumentProcessor
ProcessingContext
ProcessingResult
CanonicalDocument
DocumentProcessorRegistry
```

### PDF

```text
PdfDocumentProcessor
OpenDataLoaderAdapter
```

### Pipeline

```text
document.available
       ↓
Synvault
       ↓
DocumentProcessorRegistry
       ↓
PdfDocumentProcessor
       ↓
OpenDataLoaderAdapter
       ↓
CanonicalDocument
       ↓
Knowledge processing
```

------

# 27. Final Architecture Principle

The architecture can be summarized as three questions:

```text
┌──────────────────────────────────────────────────────────┐
│                                                          │
│  LUCENTRIX                                               │
│                                                          │
│  "HOW DO WE GET IT?"                                     │
│                                                          │
│  Source APIs, search, events, crawling, retrieval,       │
│  synchronization, provenance                            │
│                                                          │
└───────────────────────────┬──────────────────────────────┘
                            │
                            ▼
┌──────────────────────────────────────────────────────────┐
│                                                          │
│  SYNANTON DOCUMENT PROCESSING                            │
│                                                          │
│  "WHAT IS IN IT?"                                        │
│                                                          │
│  Extraction, structure, normalization                    │
│                                                          │
│              ┌─────────────────────┐                     │
│              │ Processor Adapter   │                     │
│              │      / Cloak       │                     │
│              └──────────┬──────────┘                     │
│                         │                                │
│                    OpenDataLoader                        │
│                    or another processor                  │
│                                                          │
└───────────────────────────┬──────────────────────────────┘
                            │
                            ▼
┌──────────────────────────────────────────────────────────┐
│                                                          │
│  SYNANTON KNOWLEDGE PROCESSING                           │
│                                                          │
│  "WHAT DOES IT MEAN?"                                    │
│                                                          │
│  Chunking, entities, relationships, ontology, indexing,  │
│  knowledge                                                │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

**Lucentrix knows the source.**

**Synanton owns document processing.**

**OpenDataLoader is a cloaked implementation of PDF processing.**

**Synanton owns the canonical document and knowledge models.**

This gives the system a clean dependency direction and makes both sides independently evolvable: Lucentrix can add increasingly sophisticated enterprise connectors without becoming a document-processing platform, while Synanton can replace OpenDataLoader or add entirely different PDF processors without changing the ingestion architecture.