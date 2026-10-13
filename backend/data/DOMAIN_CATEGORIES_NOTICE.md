# domain-categories.json

Maps ~12.5k popular domains to one of SurfSense's categories. Generated 2026-10-12.

- Domain source: the Majestic Million (https://majestic.com/reports/majestic-million), top 20,000 by rank, licensed CC BY 3.0 (https://creativecommons.org/licenses/by/3.0/). Attribution: "Majestic Million data by Majestic".
- Labels: assigned by Claude from its own knowledge, with no web lookups. Anything uncertain was left out (treated as "Other" by the app). An independent blind re-labeling of a 300-domain sample agreed on 97.8% of the domains where both labelers chose a category; domains where they conflicted were removed.
- Ranking is by backlinks, not visits, so coverage of what people actually browse is partial. Lookups should strip a leading `www.` and also try the parent registrable domain.
- Regenerate rather than hand-edit: edits are overwritten.
