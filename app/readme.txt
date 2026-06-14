In the /app/src/main/res/layout
open the xml file:

Set this:

                <TextureView
                    android:id="@+id/textureView"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent" />


                    To:



                <SurfaceView
                    android:id="@+id/SurfaceView"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent" />


In the older versions!
