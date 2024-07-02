 <?php
// the message
$msg = "le courant\nest coupe";

// use wordwrap() if lines are longer than 70 characters
$msg = wordwrap($msg,70);

// send email
mail("kevin.oudelet@gmail.com","alerte!",$msg);
?> 
