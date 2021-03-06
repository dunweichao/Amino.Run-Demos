package amino.run.appexamples.minnietwitter.glue;

import android.app.Activity;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.example.minnietwitter.R;

import amino.run.appexamples.minnietwitter.app.User;
import amino.run.appexamples.minnietwitter.device.generator.TwitterWorldGenerator;

public class RegisterActivity extends Activity {
    AlertDialogManager alert = new AlertDialogManager();
    public User user;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.register_user);

        TextView loginScreen = (TextView) findViewById(R.id.link_to_login);

        loginScreen.setOnClickListener(new View.OnClickListener() {

            public void onClick(View arg0) {
                finish();
            }
        });
    }

    public void registerUser(View view) {
        EditText editText1 = (EditText) findViewById(R.id.reg_fullname);
        String username = editText1.getText().toString();
        EditText editText2 = (EditText) findViewById(R.id.reg_password);
        String password = editText2.getText().toString();
        if(username.matches("") || password.matches("")) {
            Toast.makeText(this, "Username, Password cannot be empty!!", Toast.LENGTH_SHORT).show();
        } else {
            new NewUser().execute(username, password);
        }
    }

    private class NewUser extends AsyncTask<String, Void, User> {
        protected User doInBackground(String... params) {
            try {
                user = TwitterWorldGenerator.registerUser(params[0], params[1]);
            } catch (Exception e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            return user;
        }
        @Override
        protected void onPostExecute(User user) {
            if (user != null) {
                alert.showAlertDialog(RegisterActivity.this, "Registration Successful", "User Successfully Registered. Please Login.");
            } else {
                alert.showAlertDialog(RegisterActivity.this, "Registration Failed", "User by that name already exists. Please select some different Username.");
            }
        }
    }
}
