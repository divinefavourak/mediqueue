# Image prompts for MediQueue

For ChatGPT, or any image model. Working notes.

## Where an image actually helps

The landing hero currently *is* the live status card — the product demonstrating itself.
Adding a photograph beside it gives the hero two focal points competing for the same
glance. Stitch's reference used a photo *instead of* a live card, not alongside one.

So an image is worth having in one of these places, not all three:

1. **Replacing the hero card** — warmer and more human, but you lose the "here is the
   thing working" demonstration.
2. **A band further down the page**, between the feature cards and the departments.
3. **The social preview (Open Graph) image.** This is the one with no downside. A shared
   MediQueue link currently previews as a blank card.

---

## Main prompt — documentary photograph

```
Create a photograph for the hero of a healthcare web app called MediQueue, used by
patients at public health centres in Nigeria to check their place in a queue from
their phone instead of waiting in a crowded hall.

SUBJECT
A Nigerian woman in her late twenties standing just outside a public health centre in
Lagos, looking down at her phone with a calm, slightly relieved expression. She is
checking something quickly, not scrolling. Simple patterned blouse, a small bag over
one shoulder.

SETTING
Bright mid-morning daylight. Behind her, softly out of focus: the health centre
entrance, pale painted concrete walls, a covered walkway with a corrugated roof, and
a few people sitting on a shaded bench further back. Ordinary, well-used, not new.

COMPOSITION
She sits in the right two-thirds of the frame, shot at chest height. Keep the left
third clean and uncluttered so a headline can sit over it. Shallow depth of field —
she is sharp, the building soft. 16:9 landscape.

COLOUR
Natural warm daylight. Deep emerald green and dark navy occur naturally in the
environment — a painted door frame, a bench, her clothing. Off-white walls. Avoid
the cold blue-grey cast of corporate hospital photography.

STYLE
Honest documentary photography. Natural skin tones, real texture, no heavy
retouching, no stock-photo staging or posed smile at the camera.

AVOID
- Any visible text, letters, numbers, signage lettering, or phone screen content
- Western hospital interiors, scrubs, stethoscopes, clipboards
- Anyone in medical uniform; this is a patient, not a clinician
- Cool blue clinical lighting
```

## Variant — flat illustration

Safer for a submitted project: no uncanny faces, and no question about whether an
AI-generated photograph of a person belongs in coursework.

```
Same scene and palette, but as a flat vector illustration: bold 2px navy outlines,
solid emerald and off-white fills, no gradients, no soft shadows. Geometric and
confident, in the style of a public-health information poster. Simplified faces
without fine detail.
```

## Variant — social preview

```
Same subject and palette, composed for 1200x630 with the subject on the right and
generous empty space on the left half for a title overlay. Slightly darker overall so
white text reads against it.
```

---

## Why "avoid text" matters most

Image models render lettering as convincing-looking gibberish. The Stitch reference photo
has a wall board covered in garbled characters — acceptable in a mockup, embarrassing in a
submission. Excluding text entirely and letting the real HTML supply every word is the
only reliable approach.

Naming the country also changes the output substantially. "Hospital" alone defaults to
Western private-clinic imagery: glass, blue light, scrubs. Naming Lagos, the covered
walkway and the painted concrete gets the setting the specification actually describes.

## Using the output

Save to `src/main/resources/static/img/`. Export at roughly twice the display size for
sharp rendering on phones, then compress — a hero photograph above about 250 KB is a real
cost on a Nigerian mobile connection, which is the network this product assumes.

For the social preview, add to the `<head>` of `index.html`:

```html
<meta property="og:title" content="MediQueue — track your turn">
<meta property="og:description" content="Book at a public health centre and watch your place in the queue from your phone.">
<meta property="og:image" content="https://your-domain/img/social.jpg">
<meta name="twitter:card" content="summary_large_image">
```
