# Susanoo 2.0 (Fabric 1.20.1)

## Compiler
Java 17 + connexion internet, puis dans ce dossier :
    ./gradlew build        (Windows : gradlew.bat build)
Le mod est généré dans build/libs/susanoo-2.0.0.jar (à mettre dans .minecraft/mods avec Fabric API 0.92.x).

## Touches (modifiables dans Options > Contrôles > Susanoo)
- G : invoquer / renvoyer le Susanoo (il faut un Mangekyō dans l'inventaire ; clic droit sur l'objet fait pareil)
- H : forme suivante (Maj+H : précédente) : Côtes -> Squelette -> Armure -> Parfaite (géante)
- R : attaque (maintenir pour enchaîner)
- V : technique spéciale (flèche / onde de chakra)

## Couleurs
Madara = bleu (objet `mangekyo`, recette d'origine), Itachi = rouge, Sasuke = violet, Shisui = vert, Obito = orange.
Craft : Mangekyō quelconque + teinture (bleue, rouge, violette, verte, orange).

## Physique
Le Susanoo enveloppe le joueur et le suit sans retard. Il absorbe les dégâts (bouclier : 20/40/80/160 PV selon la forme,
se régénère hors combat), annule chute et feu, repousse moins (anti-recul formes 2-4) et consomme de la faim.
Les formes Armure/Parfaite brisent les blocs tendres (si mobGriefing est actif).
Réglages : tableaux de constantes en haut de SusanooManager.java et SusanooEntity.java.
