"""Face Lab — evolve new species faces from votes.

Modules:
    palettes  colour banks, parsed once from the frontend's face-genome.ts
    genome    the genome schema, hard constraints, seeded sampling and mixing
    reference the 6 shipped species expressed as genomes (generation-0 anchors)
    features  genome -> feature vector
    learner   bootstrap GradientBoosting ensemble: score + uncertainty
    ga        model-guided generation builder (elite / crossover / explore / random)
    store     the on-disk layout shared with DevFaceLabController
    codegen   palette + plumbing export for a winning genome
    cli       command line entry point
"""

__all__ = ["palettes", "genome", "reference", "features", "learner", "ga", "store", "codegen"]
