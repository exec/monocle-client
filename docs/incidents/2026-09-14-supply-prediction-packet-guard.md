# Supply prediction and packet guards

Minecraft performs local placement prediction before sending the interaction packet. During that callback, a crew guard can see the predicted container before later Restock flags establish ownership and mistakenly cancel its own placement.

The validated in-flight target and footing receive a callback-scoped local exemption after existing peer protection has been checked. Server resolution is still required before a predicted container becomes owned. Cancelled-prediction reconciliation remains necessary; removing it would promote ghosts.

Regression checks cover the before-send ordering, paired containers, neighboring protection and expiration of the temporary exemption.
