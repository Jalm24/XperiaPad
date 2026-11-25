#ifndef XPD_TRUSTED_H
#define XPD_TRUSTED_H

/* Marca como trusted cada host enlazado sin entrada en el almacen de
 * BlueZ. Devuelve cuantas entradas agrego (-1 si no hay almacen/bondings);
 * *written = 1 si el archivo trusted fue modificado. */
int trusted_sweep(int *written);

#endif
