# Caustica Development Rules

- Do not add sampled area lights, analytic torch lights, torch next-event estimation, or a separate torch-light list.
- Torch-lighting work must perfect the existing emissive-material and path-transport implementation.
- Treat a brighter visible torch surface, brighter rare fireflies, and brighter environmental illumination as separate proof points. Do not claim the torch-emission control works until controlled runtime evidence shows its effective emissive radiance changes.
- Preserve texture-authored emissive masks so only authored torch texels emit; intensity controls must scale that emissive signal rather than introduce replacement raster-style or analytic lighting.
