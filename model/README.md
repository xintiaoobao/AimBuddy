# AimBuddy Model Store (model folder)

Each model must be in its own subfolder under `model/`.

Required layout for downloadable models:

- `model/<model-id>/model.json`
- `model/<model-id>/<any-name>.param`
- `model/<model-id>/<any-name>.bin`

Metadata format (`model.json`):

```json
{
  "title": "Model Name",
  "description": "What this model is for",
  "demoOnly": false
}
```

For demo entries that should appear in the store but not be downloadable:

```json
{
  "title": "Demo Model",
  "description": "Metadata-only demo entry",
  "demoOnly": true
}
```

Notes:

- App prefers `model/` folder.
- App still supports legacy `models/` folder for backward compatibility.
- Each model folder is treated independently.
