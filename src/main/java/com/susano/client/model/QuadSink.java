package com.susano.client.model;

/** Recoit les quads generes par {@link SusanooModel} (coordonnees en espace modele). */
public interface QuadSink {
    /** Piece normale du corps. */
    int TAG_BODY = 0;
    /** Piece lumineuse (yeux, noyau) : dessinee uniquement dans la passe de lueur. */
    int TAG_BRIGHT = 1;
    /** Fleche / projectile de chakra : dessine uniquement dans la passe de lueur. */
    int TAG_ARROW = 2;

    /**
     * @param p        12 floats : 4 sommets (x,y,z)
     * @param nx,ny,nz normale sortante de la face
     * @param tag      TAG_BODY, TAG_BRIGHT ou TAG_ARROW
     */
    void quad(float[] p, float nx, float ny, float nz, int tag);
}
