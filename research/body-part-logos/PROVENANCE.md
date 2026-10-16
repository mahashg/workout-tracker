# Body-part logos — provenance

Source: OpenStax, *Anatomy and Physiology* (2016), chart "Anterior and Posterior Views of Muscles", via Wikimedia Commons.

License: CC BY 4.0. Attribution: OpenStax — Anatomy and Physiology (CC BY 4.0).

Derivation: labels removed, non-target muscles desaturated, target muscle group kept in full red via region masks (see `gen.py`, applied to `front_clean.png` / `back_clean.png` in this folder).

Shipped derivatives: the `logo_<part>.jpg` files in `app/src/main/assets/body-part-logos/` are the derivatives bundled with the app; the source/clean images and generation script in this folder are not bundled.
